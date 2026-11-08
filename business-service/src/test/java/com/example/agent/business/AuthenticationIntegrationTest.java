package com.example.agent.business;

import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实账号加载、BCrypt、认证主体、Session、CSRF 和接口权限的完整闭环。
 * 仅替换外部客户端，不 Mock 身份来源，也不使用 WithMockUser 绕过登录。
 */
@SpringBootTest(classes = BusinessServiceApplication.class, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "dify.api-key=test-dify-key",
        "dify.base-url=http://dify.invalid/v1",
        "app.security.dev.alice-password=Alice-test-123",
        "app.security.dev.bob-password=Bob-test-123",
        "logging.file.name=target/test-logs/authentication.log"
})
@ActiveProfiles({"dev", "dev-memory", "no-db-test"})
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @MockitoBean
    private ModelClient modelClient;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/actuator/info"})
    void shouldRejectAnonymousQueriesWithoutRedirecting(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/chat", "/api/chat/dify"})
    void shouldRejectAnonymousChatBeforeCallingClients(String path) throws Exception {
        // 提供合法 CSRF，单独验证认证拦截，避免先命中 CSRF 错误。
        mvc.perform(post(path).with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(request().asyncNotStarted());
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/login", "/api/auth/logout", "/api/chat/chat", "/api/chat/dify"})
    void shouldRejectUnsafeRequestsWithoutCsrf(String path) throws Exception {
        mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,wrong-password", "missing-user,wrong-password"})
    void shouldReturnSameFailureForWrongPasswordAndUnknownAccount(String username, String password)
            throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf().asHeader())
                        .param("username", username).param("password", password))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
                .andExpect(jsonPath("$.detail").value("用户名或密码错误"))
                .andExpect(header().doesNotExist("Location"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldNotTreatCsrfSessionAsLogin() throws Exception {
        ClientState anonymous = obtainCsrf(null);
        mvc.perform(get("/api/auth/me").session(anonymous.session()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,Alice-test-123,demo-user-1", "bob,Bob-test-123,demo-user-2"})
    void shouldUseAuthenticatedIdentityRotateCsrfAndInvalidateLogout(
            String username, String password, String userId) throws Exception {
        ClientState anonymous = obtainCsrf(null);
        MockHttpSession session = authenticate(anonymous, username, password);
        expectIdentity(session, userId);

        // 登录后旧 Token 必须失效；不能因此触发任何业务操作。
        mvc.perform(post("/api/chat/dify").session(session)
                        .header(anonymous.headerName(), anonymous.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        verifyNoInteractions(modelClient, difyWorkflowClient);

        ClientState loggedIn = obtainCsrf(session);
        when(difyWorkflowClient.run("你好", userId)).thenReturn("测试回答");
        mvc.perform(post("/api/chat/dify").session(session)
                        .header(loggedIn.headerName(), loggedIn.token())
                        .header("X-User-Id", "forged-user").header("X-Tenant-Id", "forged-tenant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("测试回答"));
        verify(difyWorkflowClient).run("你好", userId);

        logout(loggedIn);
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(modelClient);
    }

    @Test
    void shouldKeepUsersSessionsIndependentAndPermitLoginAgainAfterCredentialErasure() throws Exception {
        ClientState alice = login("alice", "Alice-test-123");
        ClientState bob = login("bob", "Bob-test-123");
        expectIdentity(alice.session(), "demo-user-1");
        expectIdentity(bob.session(), "demo-user-2");

        logout(alice);
        expectIdentity(bob.session(), "demo-user-2");

        // 第一次成功认证清理了主体密码，但不能破坏账号数据源。
        ClientState aliceAgain = login("alice", "Alice-test-123");
        expectIdentity(aliceAgain.session(), "demo-user-1");
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldDenyUnregisteredEndpointEvenAfterLogin() throws Exception {
        ClientState user = login("alice", "Alice-test-123");
        mvc.perform(get("/api/not-registered").session(user.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldStreamWithRealAuthenticatedSessionAndCompleteAsyncDispatch() throws Exception {
        ClientState user = login("alice", "Alice-test-123");
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("已登录，");
            onDelta.accept("开始回答。");
            return new ModelResult("已登录，开始回答。", List.of(), List.of());
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult stream = mvc.perform(post("/api/chat/chat").session(user.session())
                        .header(user.headerName(), user.token())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(5_000L);
        mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("event: delta\ndata: {\"text\":\"已登录，\"}",
                                "event: delta\ndata: {\"text\":\"开始回答。\"}")
                        .endsWith("event: done\ndata: {}\n\n")
                        .doesNotContain("event: error"));
        verify(modelClient).generateStream(any(ModelInput.class), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({
            "alice,Alice-test-123,chat:agent:invoke|chat:workflow:invoke|order:query|logistics:query",
            "bob,Bob-test-123,chat:workflow:invoke"
    })
    void shouldStoreExactAccountPermissionsInAuthenticatedSession(
            String username, String password, String expectedPermissions) throws Exception {
        ClientState user = login(username, password);
        SecurityContext saved = (SecurityContext) user.session().getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

        assertThat(saved.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(expectedPermissions.split("\\|"));
        AuthenticatedUser principal = (AuthenticatedUser) saved.getAuthentication().getPrincipal();
        assertThat(principal.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(expectedPermissions.split("\\|"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    void shouldRejectBobAgentCallWithoutExpiringHisWorkflowSession() throws Exception {
        ClientState bob = login("bob", "Bob-test-123");

        // 持有工作流权限不能调用 Agent；拒绝必须发生在异步任务和模型调用前。
        mvc.perform(post("/api/chat/chat").session(bob.session())
                        .header(bob.headerName(), bob.token())
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("当前请求不允许访问"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(request().asyncNotStarted());
        verifyNoInteractions(modelClient, difyWorkflowClient);

        // 业务权限不足不等于会话失效，仍能读取自己并调用被授予的入口。
        expectIdentity(bob.session(), "demo-user-2");
        when(difyWorkflowClient.run("继续使用工作流", "demo-user-2")).thenReturn("工作流回答");
        mvc.perform(post("/api/chat/dify").session(bob.session())
                        .header(bob.headerName(), bob.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"继续使用工作流\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("工作流回答"));
        verify(difyWorkflowClient).run("继续使用工作流", "demo-user-2");
        verifyNoInteractions(modelClient);
    }

    @Test
    void shouldNotAcceptPermissionOrIdentityClaimsFromRequestHeaders() throws Exception {
        ClientState bob = login("bob", "Bob-test-123");

        mvc.perform(post("/api/chat/chat").session(bob.session())
                        .header(bob.headerName(), bob.token())
                        .header("X-Permissions", "chat:agent:invoke")
                        .header("X-User-Id", "demo-user-1")
                        .header("X-Tenant-Id", "forged-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(request().asyncNotStarted());
        expectIdentity(bob.session(), "demo-user-2");
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @CsvSource({"alice,Alice-test-123", "bob,Bob-test-123"})
    void shouldRequireCsrfBeforeEvaluatingGrantedOrMissingAgentPermission(
            String username, String password) throws Exception {
        ClientState user = login(username, password);

        // alice 有 Agent 权限、bob 没有，但缺失 CSRF 均应先被 CSRF 过滤器拒绝。
        mvc.perform(post("/api/chat/chat").session(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"))
                .andExpect(request().asyncNotStarted());
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    private ClientState login(String username, String password) throws Exception {
        return obtainCsrf(authenticate(obtainCsrf(null), username, password));
    }

    private MockHttpSession authenticate(ClientState anonymous, String username, String password)
            throws Exception {
        String previousSessionId = anonymous.session().getId();
        MvcResult result = mvc.perform(post("/api/auth/login").session(anonymous.session())
                        .header(anonymous.headerName(), anonymous.token())
                        .param("username", username).param("password", password))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Location")).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getId()).isNotEqualTo(previousSessionId);

        SecurityContext saved = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(saved).isNotNull();
        assertThat(saved.getAuthentication().getCredentials()).isNull();
        assertThat(saved.getAuthentication().getPrincipal()).isInstanceOf(AuthenticatedUser.class);
        assertThat(((AuthenticatedUser) saved.getAuthentication().getPrincipal()).getPassword()).isNull();
        return session;
    }

    private void expectIdentity(MockHttpSession session, String userId) throws Exception {
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.tenantId").value("demo-tenant-1"))
                .andExpect(jsonPath("$.callerType").value("USER"))
                .andExpect(jsonPath("$.callerId").value(userId))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    private void logout(ClientState user) throws Exception {
        mvc.perform(post("/api/auth/logout").session(user.session())
                        .header(user.headerName(), user.token()))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Location"));
    }

    private ClientState obtainCsrf(MockHttpSession existing) throws Exception {
        var request = get("/api/auth/csrf");
        if (existing != null) {
            request.session(existing);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(body.path("headerName").asText()).isNotBlank();
        assertThat(body.path("token").asText()).isNotBlank();
        return new ClientState(session, body.path("headerName").asText(), body.path("token").asText());
    }

    private record ClientState(MockHttpSession session, String headerName, String token) {
    }
}
