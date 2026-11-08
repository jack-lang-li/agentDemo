package com.example.agent.business;

import com.example.agent.business.identity.adapter.persistence.DatabaseUserDetailsService;
import com.example.agent.business.identity.adapter.persistence.mapper.LocalAccountMapper;
import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据库账号来源的 HTTP 认证闭环：保留真实 UserDetailsService、BCrypt 和安全过滤器。
 * 只模拟 Mapper、事务管理器和外部客户端，不连接开发数据库，也不绕过登录建立身份。
 * MySQL 对 SQL、外键及事务隔离的实际执行仍由独立数据库验证负责。
 */
@SpringBootTest(classes = BusinessServiceApplication.class, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "dify.api-key=test-dify-key",
        "dify.base-url=http://dify.invalid/v1",
        "app.security.tenant-id=db-tenant-1",
        "logging.file.name=target/test-logs/database-authentication.log"
})
@ActiveProfiles({"dev", "no-db-test"})
@AutoConfigureMockMvc
@Import(DatabaseAuthenticationIntegrationTest.IsolatedTransactions.class)
class DatabaseAuthenticationIntegrationTest {

    private static final String TENANT = "db-tenant-1";
    private static final String PASSWORD = "Db-test-123";
    private static final String ALICE_ID = "db-user-alice";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ApplicationContext context;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private LocalAccountMapper accountMapper;
    @MockitoBean private ModelClient modelClient;
    @MockitoBean private DifyWorkflowClient difyWorkflowClient;

    private String aliceHash;
    private List<String> alicePermissions;
    private int aliceEnabled;

    @BeforeEach
    void setUpStorage() {
        String storedHash = passwordEncoder.encode(PASSWORD);
        aliceHash = storedHash;
        aliceEnabled = 1;
        alicePermissions = List.of("chat:agent:invoke", "chat:workflow:invoke");
        when(accountMapper.findLoginAccount(anyString(), anyString())).thenAnswer(invocation -> {
            if (!TENANT.equals(invocation.getArgument(0))) {
                return null;
            }
            String username = invocation.getArgument(1);
            return switch (username) {
                case "alice" -> account(username, ALICE_ID, aliceHash, aliceEnabled, 1);
                case "bob" -> account(username, "db-user-bob", storedHash, 1, 1);
                case "no-permissions" -> account(username, "db-user-no-permissions", storedHash, 1, 1);
                case "user-disabled" -> account(username, "db-user-user-disabled", storedHash, 0, 1);
                case "account-disabled" -> account(username, "db-user-account-disabled", storedHash, 1, 0);
                default -> null;
            };
        });
        when(accountMapper.findPermissionCodes(anyString(), anyString())).thenAnswer(invocation -> {
            String userId = invocation.getArgument(1);
            if (!TENANT.equals(invocation.getArgument(0))) {
                return List.of();
            }
            return switch (userId) {
                case ALICE_ID -> alicePermissions;
                case "db-user-bob" -> List.of("chat:workflow:invoke");
                default -> List.of();
            };
        });
        clearInvocations(transactionManager);
    }

    @Test
    void shouldSelectOnlyDatabaseAccountSourceWithoutDevMemoryProfile() {
        var sources = context.getBeansOfType(UserDetailsService.class);
        assertThat(sources).hasSize(1);
        assertThat(AopUtils.getTargetClass(sources.values().iterator().next()))
                .isEqualTo(DatabaseUserDetailsService.class);
        assertThat(context.containsBean("devUserDetailsService")).isFalse();
        verifyNoInteractions(accountMapper, modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,db-user-alice", "bob,db-user-bob"})
    void shouldUseDatabaseIdentityAndPermissionsThenInvalidateLogout(String username, String userId)
            throws Exception {
        ClientState user = login(username, PASSWORD);
        expectIdentity(user.session(), userId);
        assertThat(authentication(user.session()).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(username.equals("alice")
                        ? new String[]{"chat:agent:invoke", "chat:workflow:invoke"}
                        : new String[]{"chat:workflow:invoke"});

        // 验证事务拦截器接收到只读、可重复读要求；不声称已经验证真实数据库隔离。
        var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        verify(transactionManager).commit(any(TransactionStatus.class));

        when(difyWorkflowClient.run("你好", userId)).thenReturn("数据库账号回答");
        mvc.perform(post("/api/chat/dify").session(user.session())
                        .header(user.headerName(), user.token())
                        .header("X-User-Id", "forged-user").header("X-Tenant-Id", "forged-tenant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("数据库账号回答"));
        verify(accountMapper).findLoginAccount(TENANT, username);
        verify(accountMapper).findPermissionCodes(TENANT, userId);
        verify(difyWorkflowClient).run("你好", userId);
        verifyNoInteractions(modelClient);

        mvc.perform(post("/api/auth/logout").session(user.session())
                        .header(user.headerName(), user.token()))
                .andExpect(status().isNoContent());
        assertThat(user.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldPermitAgentStreamWithDatabasePermission() throws Exception {
        ClientState alice = login("alice", PASSWORD);
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("数据库账号回答");
            return new ModelResult("数据库账号回答", List.of(), List.of());
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult stream = mvc.perform(post("/api/chat/chat").session(alice.session())
                        .header(alice.headerName(), alice.token())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(5_000L);
        mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("数据库账号回答")
                        .endsWith("event: done\ndata: {}\n\n")
                        .doesNotContain("event: error"));
        verify(modelClient).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldRejectAgentWhenDatabaseOnlyGrantsWorkflowPermission() throws Exception {
        ClientState bob = login("bob", PASSWORD);
        expectForbiddenChat(bob, "/api/chat/chat");
        expectIdentity(bob.session(), "db-user-bob");
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user-disabled", "account-disabled"})
    void shouldRejectDisabledUserOrLocalAccountWithoutLoadingPermissions(String username) throws Exception {
        ClientState anonymous = obtainCsrf(null);
        expectLoginFailure(anonymous, username, PASSWORD);
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,wrong-password", "missing-user,Db-test-123"})
    void shouldGiveSameResponseForWrongPasswordAndUnknownAccount(String username, String password)
            throws Exception {
        expectLoginFailure(obtainCsrf(null), username, password);
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/chat", "/api/chat/dify"})
    void shouldAllowLoginWithoutPermissionsButDenyBusinessEntry(String path) throws Exception {
        ClientState user = login("no-permissions", PASSWORD);
        expectIdentity(user.session(), "db-user-no-permissions");
        assertThat(authentication(user.session()).getAuthorities()).isEmpty();
        expectForbiddenChat(user, path);
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "permissions"})
    void shouldReturnServiceUnavailableAndRollbackOnStorageFailure(String failedRead) throws Exception {
        var failure = new DataAccessResourceFailureException("private-storage-error");
        if (failedRead.equals("account")) {
            when(accountMapper.findLoginAccount(TENANT, "alice")).thenThrow(failure);
        } else {
            when(accountMapper.findPermissionCodes(TENANT, ALICE_ID)).thenThrow(failure);
        }
        expectServiceUnavailable(obtainCsrf(null));
        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(transactionManager, never()).commit(any(TransactionStatus.class));
        if (failedRead.equals("account")) {
            verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
        }
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldFailAuthenticationIfStorageReturnsAnotherTenant() throws Exception {
        LocalAccountRow row = account("alice", ALICE_ID, aliceHash, 1, 1);
        row.setTenantId("another-tenant");
        when(accountMapper.findLoginAccount(TENANT, "alice")).thenReturn(row);

        expectServiceUnavailable(obtainCsrf(null));
        verify(accountMapper, never()).findPermissionCodes(anyString(), anyString());
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldReadUpdatedPermissionsOnNewLoginAndKeepExistingSessionSnapshot() throws Exception {
        ClientState previous = login("alice", PASSWORD);
        alicePermissions = List.of("chat:workflow:invoke");
        ClientState current = login("alice", PASSWORD);

        assertThat(authentication(previous.session()).getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("chat:agent:invoke", "chat:workflow:invoke");
        assertThat(authentication(current.session()).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("chat:workflow:invoke");
        expectForbiddenChat(current, "/api/chat/chat");
        expectIdentity(previous.session(), ALICE_ID);
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldRejectNewLoginAfterDisableWhileExistingSessionRemainsValid() throws Exception {
        ClientState previous = login("alice", PASSWORD);
        aliceEnabled = 0;

        expectLoginFailure(obtainCsrf(null), "alice", PASSWORD);
        // 本阶段停用只影响新登录，已有会话的主动撤销留到后续阶段。
        expectIdentity(previous.session(), ALICE_ID);
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldUseChangedPasswordHashOnNextLoginWithoutCachingOldCredentials() throws Exception {
        ClientState previous = login("alice", PASSWORD);
        aliceHash = passwordEncoder.encode("Changed-test-456");

        expectLoginFailure(obtainCsrf(null), "alice", PASSWORD);
        expectIdentity(login("alice", "Changed-test-456").session(), ALICE_ID);
        expectIdentity(previous.session(), ALICE_ID);
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldCheckCsrfBeforeReadingDatabaseForLogin() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", "alice").param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(accountMapper, modelClient, difyWorkflowClient);
    }

    private ClientState login(String username, String password) throws Exception {
        ClientState anonymous = obtainCsrf(null);
        String previousSessionId = anonymous.session().getId();
        MvcResult result = attemptLogin(anonymous, username, password)
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Location")).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotEqualTo(previousSessionId);
        Authentication authentication = authentication(session);
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) authentication.getPrincipal()).getPassword()).isNull();
        return obtainCsrf(session);
    }

    private ResultActions attemptLogin(ClientState anonymous, String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").session(anonymous.session())
                .header(anonymous.headerName(), anonymous.token())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", username).param("password", password));
    }

    private void expectLoginFailure(ClientState anonymous, String username, String password) throws Exception {
        attemptLogin(anonymous, username, password)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"));
        mvc.perform(get("/api/auth/me").session(anonymous.session()))
                .andExpect(status().isUnauthorized());
    }

    private void expectServiceUnavailable(ClientState anonymous) throws Exception {
        attemptLogin(anonymous, "alice", PASSWORD)
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("AUTH_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("登录服务暂时不可用，请稍后重试"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-storage-error"))));
        mvc.perform(get("/api/auth/me").session(anonymous.session()))
                .andExpect(status().isUnauthorized());
    }

    private void expectIdentity(MockHttpSession session, String userId) throws Exception {
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.callerType").value("USER"))
                .andExpect(jsonPath("$.callerId").value(userId))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    private void expectForbiddenChat(ClientState user, String path) throws Exception {
        mvc.perform(post(path).session(user.session())
                        .header(user.headerName(), user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(request().asyncNotStarted());
    }

    private ClientState obtainCsrf(MockHttpSession existing) throws Exception {
        var request = get("/api/auth/csrf");
        if (existing != null) {
            request.session(existing);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(body.path("headerName").asText()).isNotBlank();
        assertThat(body.path("token").asText()).isNotBlank();
        return new ClientState(session, body.path("headerName").asText(), body.path("token").asText());
    }

    private static Authentication authentication(MockHttpSession session) {
        SecurityContext saved = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved).isNotNull();
        return saved.getAuthentication();
    }

    private static LocalAccountRow account(
            String username, String userId, String hash, int userEnabled, int accountEnabled) {
        LocalAccountRow row = new LocalAccountRow();
        row.setTenantId(TENANT);
        row.setUserId(userId);
        row.setUsername(username);
        row.setPasswordHash(hash);
        row.setUserEnabled(userEnabled);
        row.setAccountEnabled(accountEnabled);
        return row;
    }

    private record ClientState(MockHttpSession session, String headerName, String token) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class IsolatedTransactions {
        @Bean
        PlatformTransactionManager transactionManager() {
            // 让真实 @Transactional 拦截器运行，但完全不访问 JDBC 连接。
            PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any(TransactionDefinition.class)))
                    .thenAnswer(invocation -> new SimpleTransactionStatus());
            return manager;
        }
    }
}
