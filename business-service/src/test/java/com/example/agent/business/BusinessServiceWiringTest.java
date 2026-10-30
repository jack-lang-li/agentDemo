package com.example.agent.business;

import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.DefaultAgentRunner;
import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 保护真实装配，不使用 ModelClient Mock，避免业务集成测试掩盖缺失 Bean。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "dify.api-key=test-dify-key",
        "dify.base-url=http://dify.invalid/v1",
        "logging.file.name=target/test-logs/business-service.log",
        "app.security.dev.alice-password=Alice-test-123",
        "app.security.dev.bob-password=Bob-test-123",
        "server.servlet.session.cookie.secure=false"
})
@ActiveProfiles("dev")
class BusinessServiceWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper mapper;

    @LocalServerPort
    private int port;

    @Test
    void shouldAssembleLocalModelAgentAndDifyWithoutScanningOtherServices() {
        assertThat(context.getBeansOfType(ModelClient.class)).hasSize(1);
        assertThat(context.getBean(ModelClient.class)).isInstanceOf(ResponsesModelClient.class);
        assertThat(context.getBeansOfType(AgentRunner.class)).hasSize(1);
        assertThat(context.getBean(AgentRunner.class)).isInstanceOf(DefaultAgentRunner.class);
        assertThat(context.getBeansOfType(DifyWorkflowClient.class)).hasSize(1);
    }

    @Test
    void shouldServeHealthThroughRealHttpServer() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void shouldMaintainLoginAndLogoutThroughRealSessionCookie() throws Exception {
        // 使用真正的 HTTP 和 CookieJar，补充 MockMvc 的 session 注入验证。
        // 全程只调用认证入口，不访问模型或 Dify。
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
        HttpResponse<String> csrf = get(client, "/api/auth/csrf");
        assertThat(csrf.statusCode()).isEqualTo(200);
        assertThat(csrf.headers().allValues("set-cookie"))
                .anySatisfy(cookie -> assertThat(cookie).contains("JSESSIONID=", "HttpOnly", "SameSite=Lax"));
        JsonNode beforeLogin = mapper.readTree(csrf.body());

        HttpResponse<String> login = client.send(HttpRequest.newBuilder(endpoint("/api/auth/login"))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header(beforeLogin.path("headerName").asText(), beforeLogin.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.ofString("username=alice&password="
                                + URLEncoder.encode("Alice-test-123", StandardCharsets.UTF_8)))
                        .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(login.headers().firstValue("Location")).isEmpty();

        HttpResponse<String> me = get(client, "/api/auth/me");
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(me.body()).path("callerId").asText()).isEqualTo("demo-user-1");

        JsonNode afterLogin = mapper.readTree(get(client, "/api/auth/csrf").body());
        HttpResponse<String> logout = client.send(HttpRequest.newBuilder(endpoint("/api/auth/logout"))
                        .timeout(Duration.ofSeconds(5))
                        .header(afterLogin.path("headerName").asText(), afterLogin.path("token").asText())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(cookies.getCookieStore().getCookies())
                .noneMatch(cookie -> cookie.getName().equals("JSESSIONID"));
        assertThat(get(client, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
