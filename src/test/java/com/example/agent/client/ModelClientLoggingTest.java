package com.example.agent.client;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agent.config.ModelClientConfig;
import com.example.agent.config.ModelProperties;
import com.example.agent.client.responses.ResponsesModelClient;
import com.example.agent.client.responses.ResponsesModelCodec;
import com.example.agent.client.responses.ResponsesStreamReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelClientLoggingTest {

    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private ListAppender<ILoggingEvent> logAppender;
    private HttpServer server;
    private ModelClient client;
    private int status = 200;
    private String responseBody;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, body.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(body);
            }
            exchange.close();
        });
        server.start();
        ModelProperties properties = new ModelProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("secret-test-api-key");
        properties.setModel("test-model");
        ModelClientConfig config = new ModelClientConfig();
        ObjectMapper mapper = new ObjectMapper();
        ResponsesModelCodec codec = new ResponsesModelCodec(mapper);
        client = new ResponsesModelClient(properties, config.modelRestClient(RestClient.builder(), properties),
                config.modelStreamRestClient(properties), codec, new ResponsesStreamReader(mapper, codec));
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logAppender);
        logAppender.stop();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldLogRawBodiesWithoutConsumingResponseOrLoggingCredentials() {
        responseBody = """
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"你好，日志"}]}],"extra":"raw-field"}
                """;

        assertThat(client.chat("测试请求")).isEqualTo("你好，日志");
        assertThat(receivedBody.get()).contains("测试请求", "test-model");
        assertThat(logOutput()).contains("模型请求：", "测试请求", "模型响应：status=200", "raw-field", "你好，日志")
                .doesNotContain("secret-test-api-key", "Authorization");
    }

    @Test
    void shouldLogErrorBodyAndPreserveHttpError() {
        status = 400;
        responseBody = "{\"error\":\"invalid model\"}";

        assertThatThrownBy(() -> client.chat("测试请求"))
                .isInstanceOfSatisfying(RestClientResponseException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(400);
                    assertThat(exception.getResponseBodyAsString()).isEqualTo(responseBody);
                });
        assertThat(logOutput()).contains("模型响应：status=400", "invalid model", "模型调用失败")
                .doesNotContain("secret-test-api-key");
    }

    @Test
    void shouldLogRawBodyBeforeJsonParsingFails() {
        responseBody = "invalid-json-response";

        assertThatThrownBy(() -> client.chat("测试请求")).isInstanceOf(RestClientException.class);
        assertThat(logOutput()).contains("模型响应：status=200", responseBody, "模型调用失败");
    }

    private String logOutput() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }
}
