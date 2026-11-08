package com.example.agent.business;

import com.example.agent.business.identity.adapter.persistence.DatabaseUserDetailsService;
import com.example.agent.business.identity.adapter.persistence.mapper.LocalAccountMapper;
import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.business.chat.application.ChatService;
import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.business.logistics.tool.LogisticsTool;
import com.example.agent.business.order.adapter.persistence.mapper.OrderQueryMapper;
import com.example.agent.business.logistics.adapter.persistence.mapper.LogisticsQueryMapper;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.example.agent.testsupport.ModelFixtures.answer;
import static com.example.agent.testsupport.BusinessDataFixtures.orderRow;
import static com.example.agent.testsupport.BusinessDataFixtures.logisticsRow;
import static com.example.agent.testsupport.ModelFixtures.proposal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Autowired private ThreadPoolTaskExecutor chatStreamExecutor;

    @MockitoBean private LocalAccountMapper accountMapper;
    @MockitoBean private ModelClient modelClient;
    @MockitoBean private DifyWorkflowClient difyWorkflowClient;
    @MockitoBean private OrderQueryMapper orderMapper;
    @MockitoBean private LogisticsQueryMapper logisticsMapper;
    @MockitoSpyBean private OrderTool orderTool;
    @MockitoSpyBean private LogisticsTool logisticsTool;
    @MockitoSpyBean private ChatService chatService;

    private String aliceHash;
    private List<String> alicePermissions;
    private List<String> bobPermissions;
    private int aliceEnabled;
    private final ThreadLocal<CountDownLatch> initialDispatch = new ThreadLocal<>();

    @BeforeEach
    void setUpStorage() {
        // MockHttpServletResponse 的响应头容器不支持并发写入。
        // 仅控制测试调度：初始安全过滤器退出后再执行流式任务，仍使用真实线程池。
        chatStreamExecutor.setTaskDecorator(task -> {
            CountDownLatch gate = initialDispatch.get();
            if (gate == null) {
                return task;
            }
            return () -> {
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("MockMvc 初始派发未完成");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待初始派发时被中断", exception);
                }
                task.run();
            };
        });
        String storedHash = passwordEncoder.encode(PASSWORD);
        aliceHash = storedHash;
        aliceEnabled = 1;
        alicePermissions = List.of("chat:agent:invoke", "chat:workflow:invoke");
        bobPermissions = List.of("chat:workflow:invoke");
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
                case "db-user-bob" -> bobPermissions;
                default -> List.of();
            };
        });
        when(orderMapper.findOwnedOrder(TENANT, ALICE_ID, "10001"))
                .thenReturn(orderRow("10001", "SF_TEST_10001"));
        when(orderMapper.findOwnedOrder(TENANT, "db-user-bob", "10002"))
                .thenReturn(orderRow("10002", "SF_TEST_10002"));
        when(logisticsMapper.findOwnedLogistics(TENANT, ALICE_ID, "SF_TEST_10001"))
                .thenReturn(logisticsRow("SF_TEST_10001", "南宁转运中心"));
        when(logisticsMapper.findOwnedLogistics(TENANT, "db-user-bob", "SF_TEST_10002"))
                .thenReturn(logisticsRow("SF_TEST_10002", "广州转运中心"));
        clearInvocations(transactionManager, orderMapper, logisticsMapper);
    }

    @AfterEach
    void restoreStreamScheduling() {
        chatStreamExecutor.setTaskDecorator(null);
        initialDispatch.remove();
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

        MvcResult stream = performStream(post("/api/chat/chat").session(alice.session())
                        .header(alice.headerName(), alice.token())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"你好\"}"));
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

    @ParameterizedTest
    @MethodSource("toolGrants")
    void shouldExposeOnlyGrantedToolsAndAllowPlainChatWithoutTools(
            List<String> permissions, List<String> expectedTools) throws Exception {
        alicePermissions = permissions;
        ClientState alice = login("alice", PASSWORD);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            assertThat(input.tools()).extracting(tool -> tool.getName())
                    .containsExactlyElementsOf(expectedTools);
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("普通聊天回答");
            return answer("普通聊天回答");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        assertThat(finishStream(startStream(alice, "你好"), true)).contains("普通聊天回答");

        verify(modelClient).generateStream(any(ModelInput.class), any());
        verifyNoBusinessQueries();
        verifyNoInteractions(difyWorkflowClient);
    }

    @ParameterizedTest
    @MethodSource("forbiddenToolCalls")
    void shouldRejectForcedToolCallsDespiteForgedClientPermissions(
            List<String> permissions, String forbiddenTool, List<String> visibleTools) throws Exception {
        alicePermissions = permissions;
        ClientState alice = login("alice", PASSWORD);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            assertThat(input.tools()).extracting(tool -> tool.getName())
                    .containsExactlyElementsOf(visibleTools).doesNotContain(forbiddenTool);
            return proposal("forbidden-call", forbiddenTool,
                    "{\"orderId\":\"10001\",\"trackingNo\":\"SF_TEST_10001\"}");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult stream = performStream(post("/api/chat/chat").session(alice.session())
                        .header(alice.headerName(), alice.token())
                        .header("X-Permissions", "chat:agent:invoke,order:query,logistics:query")
                        .header("X-User-Id", "forged-user")
                        .header("X-Tenant-Id", "forged-tenant")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"message":"越权调用","userId":"forged-user",
                                 "permissionCodes":["order:query","logistics:query"],
                                 "authorization":{"permissionCodes":["order:query","logistics:query"]}}
                                """));

        assertThat(finishStream(stream, false))
                .doesNotContain("forbidden-call", "forged-user", "SF_TEST_10001", "不支持的工具");
        verify(modelClient).generateStream(any(ModelInput.class), any());
        verify(orderTool, never()).execute(any(), any());
        verify(logisticsTool, never()).execute(any(), any());
        verifyNoBusinessQueries();
        expectIdentity(alice.session(), ALICE_ID);
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldRejectTheWholeToolBatchBeforeAnEarlierAllowedQueryExecutes() throws Exception {
        alicePermissions = List.of("chat:agent:invoke", "order:query");
        ClientState alice = login("alice", PASSWORD);
        ModelResult allowed = proposal("allowed-order", "query_order", "{\"orderId\":\"10001\"}");
        ModelResult forbidden = proposal("forbidden-logistics", "query_logistics",
                "{\"trackingNo\":\"SF_TEST_10001\"}");
        when(modelClient.generateStream(any(ModelInput.class), any())).thenReturn(new ModelResult("",
                List.of(allowed.toolCalls().get(0), forbidden.toolCalls().get(0)),
                List.of(allowed.outputItems().get(0), forbidden.outputItems().get(0))));

        finishStream(startStream(alice, "同一轮包含两个工具"), false);

        verify(orderTool, never()).execute(any(), any());
        verify(logisticsTool, never()).execute(any(), any());
        verifyNoBusinessQueries();
        verify(modelClient).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({
            "order:query,query_order,orderId,10001",
            "logistics:query,query_logistics,trackingNo,SF_TEST_10001"
    })
    void shouldExecuteTheGrantedToolAndReturnItsResultToTheModel(
            String permission, String toolName, String field, String value) throws Exception {
        alicePermissions = List.of("chat:agent:invoke", permission);
        ClientState alice = login("alice", PASSWORD);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            assertThat(input.tools()).extracting(tool -> tool.getName()).containsExactly(toolName);
            if (input.turns().isEmpty()) {
                return proposal("granted-call", toolName,
                        mapper.writeValueAsString(Map.of(field, value)));
            }
            assertThat(input.turns()).hasSize(1);
            var results = input.turns().get(0).toolResults();
            assertThat(results).hasSize(1);
            assertThat(results.get(0).callId()).isEqualTo("granted-call");
            JsonNode output = mapper.readTree(results.get(0).output());
            assertThat(output.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(output.path("data").path(field).asText()).isEqualTo(value);
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("查询完成");
            return answer("查询完成");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        assertThat(finishStream(startStream(alice, "查询获准的数据"), true)).contains("查询完成");

        verify(modelClient, times(2)).generateStream(any(ModelInput.class), any());
        if (permission.equals("order:query")) {
            verify(orderMapper).findOwnedOrder(TENANT, ALICE_ID, "10001");
            verifyNoInteractions(logisticsMapper);
        } else {
            verify(logisticsMapper).findOwnedLogistics(TENANT, ALICE_ID, "SF_TEST_10001");
            verifyNoInteractions(orderMapper);
        }
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldDenyAgentEntryWhenOnlyToolPermissionsAreGranted() throws Exception {
        alicePermissions = List.of("order:query", "logistics:query");
        ClientState alice = login("alice", PASSWORD);

        expectForbiddenChat(alice, "/api/chat/chat");
        expectIdentity(alice.session(), ALICE_ID);

        verifyNoBusinessQueries();
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldKeepLoginToolSnapshotUntilTheUserLogsInAgain() throws Exception {
        alicePermissions = List.of("chat:agent:invoke", "order:query");
        ClientState previous = login("alice", PASSWORD);
        alicePermissions = List.of("chat:agent:invoke", "logistics:query");
        ClientState current = login("alice", PASSWORD);
        clearInvocations(accountMapper);
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("回答");
            return answer("回答");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        finishStream(startStream(previous, "旧会话"), true);
        finishStream(startStream(current, "新会话"), true);

        var inputs = ArgumentCaptor.forClass(ModelInput.class);
        verify(modelClient, times(2)).generateStream(inputs.capture(), any());
        assertThat(inputs.getAllValues().get(0).tools()).extracting(tool -> tool.getName())
                .containsExactly("query_order");
        assertThat(inputs.getAllValues().get(1).tools()).extracting(tool -> tool.getName())
                .containsExactly("query_logistics");
        verifyNoBusinessQueries();
        verifyNoInteractions(accountMapper, difyWorkflowClient);
    }

    @Test
    void shouldKeepIdentityPermissionsAndToolResultsSeparateForConcurrentSseUsers() throws Exception {
        alicePermissions = List.of("chat:agent:invoke", "order:query");
        bobPermissions = List.of("chat:agent:invoke", "logistics:query");
        ClientState alice = login("alice", PASSWORD);
        ClientState bob = login("bob", PASSWORD);
        CountDownLatch bothModelsStarted = new CountDownLatch(2);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            boolean isAlice = input.message().equals("alice-order");
            String toolName = isAlice ? "query_order" : "query_logistics";
            assertThat(input.tools()).extracting(tool -> tool.getName()).containsExactly(toolName);
            if (input.turns().isEmpty()) {
                bothModelsStarted.countDown();
                assertThat(bothModelsStarted.await(10, TimeUnit.SECONDS))
                        .as("两个不同用户的 SSE 必须实际重叠执行").isTrue();
                return proposal(input.message(), toolName, isAlice
                        ? "{\"orderId\":\"10001\"}" : "{\"trackingNo\":\"SF_TEST_10002\"}");
            }
            assertThat(input.turns()).hasSize(1);
            var results = input.turns().get(0).toolResults();
            assertThat(results).hasSize(1);
            assertThat(results.get(0).callId()).isEqualTo(input.message());
            JsonNode output = mapper.readTree(results.get(0).output());
            assertThat(output.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(output.path("data").path(isAlice ? "orderId" : "trackingNo").asText())
                    .isEqualTo(isAlice ? "10001" : "SF_TEST_10002");
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("完成:" + input.message());
            return answer("完成:" + input.message());
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult aliceStream = startStream(alice, "alice-order");
        MvcResult bobStream = startStream(bob, "bob-logistics");
        assertThat(finishStream(aliceStream, true)).contains("完成:alice-order").doesNotContain("完成:bob-logistics");
        assertThat(finishStream(bobStream, true)).contains("完成:bob-logistics").doesNotContain("完成:alice-order");

        var commands = ArgumentCaptor.forClass(ChatCommand.class);
        verify(chatService, times(2)).chatStream(eq(ChatExecutorCodes.LOCAL_AGENT), commands.capture(), any(), any());
        Map<String, ChatCommand> byMessage = commands.getAllValues().stream()
                .collect(Collectors.toMap(ChatCommand::message, command -> command));
        assertThat(byMessage.get("alice-order").caller().callerId()).isEqualTo(ALICE_ID);
        assertThat(byMessage.get("alice-order").caller().tenantId()).isEqualTo(TENANT);
        assertThat(byMessage.get("alice-order").authorization().permissionCodes())
                .containsExactlyInAnyOrder("chat:agent:invoke", "order:query");
        assertThat(byMessage.get("bob-logistics").caller().callerId()).isEqualTo("db-user-bob");
        assertThat(byMessage.get("bob-logistics").caller().tenantId()).isEqualTo(TENANT);
        assertThat(byMessage.get("bob-logistics").authorization().permissionCodes())
                .containsExactlyInAnyOrder("chat:agent:invoke", "logistics:query");
        verify(orderMapper).findOwnedOrder(TENANT, ALICE_ID, "10001");
        verify(logisticsMapper).findOwnedLogistics(TENANT, "db-user-bob", "SF_TEST_10002");
        verify(modelClient, times(4)).generateStream(any(ModelInput.class), any());
        expectIdentity(alice.session(), ALICE_ID);
        expectIdentity(bob.session(), "db-user-bob");
        verifyNoInteractions(difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({
            "alice,10002,db-tenant-1,db-user-bob",
            "bob,10001,db-tenant-1,db-user-alice",
            "alice,20001,other-tenant,other-user",
            "alice,99999,db-tenant-1,db-user-alice"
    })
    void shouldReturnNoAccessibleOrderDataForOtherOwnersTenantsOrMissingIds(
            String username, String orderId, String forgedTenant, String forgedOwner) throws Exception {
        allowAllToolsForBothUsers();
        ClientState user = login(username, PASSWORD);
        when(orderMapper.findOwnedOrder("other-tenant", "other-user", "20001"))
                .thenReturn(orderRow("20001", "FOREIGN_TRACK"));
        stubInaccessibleLookup("query_order", "orderId", orderId, forgedTenant, forgedOwner);

        assertThat(finishStream(startStream(user, "查询订单"), true)).contains("未找到可访问记录");

        verify(orderMapper).findOwnedOrder(TENANT, username.equals("alice") ? ALICE_ID : "db-user-bob", orderId);
        verify(modelClient, times(2)).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(logisticsMapper, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({
            "alice,SF_TEST_10002,db-tenant-1,db-user-bob",
            "bob,SF_TEST_10001,db-tenant-1,db-user-alice",
            "alice,FOREIGN_TRACK,other-tenant,other-user",
            "alice,missing,db-tenant-1,db-user-alice"
    })
    void shouldRejectKnownForeignWaybillsWithoutRevealingTheirExistence(
            String username, String trackingNo, String forgedTenant, String forgedOwner) throws Exception {
        allowAllToolsForBothUsers();
        ClientState user = login(username, PASSWORD);
        when(logisticsMapper.findOwnedLogistics("other-tenant", "other-user", "FOREIGN_TRACK"))
                .thenReturn(logisticsRow("FOREIGN_TRACK", "private-other-tenant-location"));
        stubInaccessibleLookup("query_logistics", "trackingNo", trackingNo, forgedTenant, forgedOwner);

        String body = finishStream(startStream(user, "查询已知运单"), true);
        assertThat(body).contains("未找到可访问记录").doesNotContain("private-other-tenant-location");

        verify(logisticsMapper).findOwnedLogistics(
                TENANT, username.equals("alice") ? ALICE_ID : "db-user-bob", trackingNo);
        verify(modelClient, times(2)).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(orderMapper, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,10001,SF_TEST_10001", "bob,10002,SF_TEST_10002"})
    void shouldCompleteOwnedOrderAndLogisticsChainForEitherUser(
            String username, String orderId, String trackingNo) throws Exception {
        allowAllToolsForBothUsers();
        ClientState user = login(username, PASSWORD);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            if (input.turns().isEmpty()) {
                return proposal("owned-order", "query_order", mapper.writeValueAsString(Map.of("orderId", orderId)));
            }
            JsonNode orderResult = mapper.readTree(input.turns().get(0).toolResults().get(0).output());
            assertThat(orderResult.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(orderResult.path("data").path("found").asBoolean()).isTrue();
            assertThat(orderResult.path("data").path("orderId").asText()).isEqualTo(orderId);
            assertThat(orderResult.path("data").path("trackingNo").asText()).isEqualTo(trackingNo);
            if (input.turns().size() == 1) {
                return proposal("owned-logistics", "query_logistics",
                        mapper.writeValueAsString(Map.of("trackingNo", trackingNo)));
            }
            assertThat(input.turns()).hasSize(2);
            var logisticsResult = input.turns().get(1).toolResults().get(0);
            assertThat(logisticsResult.callId()).isEqualTo("owned-logistics");
            JsonNode data = mapper.readTree(logisticsResult.output());
            assertThat(data.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(data.path("data").path("found").asBoolean()).isTrue();
            assertThat(data.path("data").path("trackingNo").asText()).isEqualTo(trackingNo);
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("本人订单物流查询完成");
            return answer("本人订单物流查询完成");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        assertThat(finishStream(startStream(user, "查询我的订单和物流"), true)).contains("本人订单物流查询完成");

        String userId = username.equals("alice") ? ALICE_ID : "db-user-bob";
        verify(orderMapper).findOwnedOrder(TENANT, userId, orderId);
        verify(logisticsMapper).findOwnedLogistics(TENANT, userId, trackingNo);
        verify(modelClient, times(3)).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"query_order,orderId,10001", "query_logistics,trackingNo,SF_TEST_10001"})
    void shouldTerminateSseWithErrorWhenBusinessStorageFails(
            String toolName, String field, String identifier) throws Exception {
        allowAllToolsForBothUsers();
        ClientState alice = login("alice", PASSWORD);
        var failure = new DataAccessResourceFailureException("private-business-storage-error");
        if (toolName.equals("query_order")) {
            when(orderMapper.findOwnedOrder(TENANT, ALICE_ID, identifier)).thenThrow(failure);
        } else {
            when(logisticsMapper.findOwnedLogistics(TENANT, ALICE_ID, identifier)).thenThrow(failure);
        }
        when(modelClient.generateStream(any(ModelInput.class), any()))
                .thenReturn(proposal("storage-call", toolName, mapper.writeValueAsString(Map.of(field, identifier))));

        assertThat(finishStream(startStream(alice, "查询"), false)).doesNotContain("private-business-storage-error");
        verify(modelClient).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldRecheckOrderOwnershipForTheNextRequestUsingTheSameLoginSession() throws Exception {
        alicePermissions = List.of("chat:agent:invoke", "order:query");
        ClientState alice = login("alice", PASSWORD);
        AtomicBoolean visible = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            if (input.turns().isEmpty()) {
                return proposal("ownership-call", "query_order", "{\"orderId\":\"10001\"}");
            }
            JsonNode result = mapper.readTree(input.turns().get(0).toolResults().get(0).output());
            assertThat(result.path("data").path("found").asBoolean()).isEqualTo(visible.get());
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept(visible.get() ? "可访问" : "不可访问");
            return answer(visible.get() ? "可访问" : "不可访问");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        assertThat(finishStream(startStream(alice, "第一次查询"), true)).contains("\"text\":\"可访问\"");
        when(orderMapper.findOwnedOrder(TENANT, ALICE_ID, "10001")).thenReturn(null);
        visible.set(false);
        assertThat(finishStream(startStream(alice, "归属变化后再次查询"), true)).contains("\"text\":\"不可访问\"");

        verify(accountMapper).findLoginAccount(TENANT, "alice");
        verify(orderMapper, times(2)).findOwnedOrder(TENANT, ALICE_ID, "10001");
        verifyNoInteractions(logisticsMapper, difyWorkflowClient);
    }

    private void allowAllToolsForBothUsers() {
        alicePermissions = List.of("chat:agent:invoke", "order:query", "logistics:query");
        bobPermissions = alicePermissions;
    }

    private void stubInaccessibleLookup(
            String toolName, String field, String identifier, String forgedTenant, String forgedOwner) {
        doAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            if (input.turns().isEmpty()) {
                return proposal("inaccessible-call", toolName, mapper.writeValueAsString(Map.of(
                        field, identifier, "tenantId", forgedTenant, "ownerUserId", forgedOwner)));
            }
            assertThat(input.turns()).hasSize(1);
            var result = input.turns().get(0).toolResults().get(0);
            assertThat(result.callId()).isEqualTo("inaccessible-call");
            JsonNode output = mapper.readTree(result.output());
            assertThat(output.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(output.path("data"))
                    .isEqualTo(mapper.valueToTree(Map.of("found", false, field, identifier)));
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("未找到可访问记录");
            return answer("未找到可访问记录");
        }).when(modelClient).generateStream(any(ModelInput.class), any());
    }

    static Stream<Arguments> toolGrants() {
        return Stream.of(
                Arguments.of(List.of("chat:agent:invoke"), List.of()),
                Arguments.of(List.of("chat:agent:invoke", "order:query"), List.of("query_order")),
                Arguments.of(List.of("chat:agent:invoke", "logistics:query"), List.of("query_logistics")),
                Arguments.of(List.of("chat:agent:invoke", "order:query", "logistics:query"),
                        List.of("query_order", "query_logistics"))
        );
    }

    static Stream<Arguments> forbiddenToolCalls() {
        return Stream.of(
                Arguments.of(List.of("chat:agent:invoke"), "query_order", List.of()),
                Arguments.of(List.of("chat:agent:invoke"), "query_logistics", List.of()),
                Arguments.of(List.of("chat:agent:invoke", "order:query"), "query_logistics", List.of("query_order")),
                Arguments.of(List.of("chat:agent:invoke", "logistics:query"), "query_order", List.of("query_logistics"))
        );
    }

    private MvcResult startStream(ClientState user, String message) throws Exception {
        return performStream(post("/api/chat/chat").session(user.session())
                        .header(user.headerName(), user.token())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(mapper.writeValueAsString(Map.of("message", message))));
    }

    private MvcResult performStream(MockHttpServletRequestBuilder requestBuilder) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        initialDispatch.set(gate);
        try {
            return mvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn();
        } finally {
            initialDispatch.remove();
            gate.countDown();
        }
    }

    private String finishStream(MvcResult stream, boolean successful) throws Exception {
        stream.getAsyncResult(15_000L);
        String body = mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        if (successful) {
            assertThat(body).endsWith("event: done\ndata: {}\n\n").doesNotContain("event: error");
        } else {
            assertThat(body).endsWith("event: error\ndata: {\"message\":\"本次生成失败，请稍后重试\"}\n\n")
                    .doesNotContain("event: done");
        }
        return body;
    }

    private void verifyNoBusinessQueries() {
        verifyNoInteractions(orderMapper);
        verifyNoInteractions(logisticsMapper);
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
