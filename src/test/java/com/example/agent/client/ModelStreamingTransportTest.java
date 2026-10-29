package com.example.agent.client;

import com.example.agent.client.responses.ResponsesModelClient;
import com.example.agent.client.responses.ResponsesModelCodec;
import com.example.agent.client.responses.ResponsesStreamReader;
import com.example.agent.config.ModelClientConfig;
import com.example.agent.config.ModelProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.agent.support.ModelFixtures.answer;
import static com.example.agent.support.ModelFixtures.completedFrame;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用本机 HTTP 握手证明流式客户端没有等待完整响应体，防止缓冲拦截器误入流式链路。 */
class ModelStreamingTransportTest {

    @Test
    void shouldDeliverFirstDeltaBeforeServerSendsCompletedResponse() throws Exception {
        CountDownLatch deltaReceived = new CountDownLatch(1);
        AtomicBoolean receivedBeforeCompletion = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"首段正文\"}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                try {
                    // 客户端收到增量才允许服务端继续结束回答，单靠完整正文无法通过此断言。
                    receivedBeforeCompletion.set(deltaReceived.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                output.write(completedFrame(answer("首段正文")).getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ModelProperties properties = new ModelProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setApiKey("test-key");
            properties.setModel("test-model");
            properties.setReadTimeoutMillis(5_000);
            ModelClientConfig config = new ModelClientConfig();
            ObjectMapper mapper = new ObjectMapper();
            ResponsesModelCodec codec = new ResponsesModelCodec(mapper);
            ModelClient client = new ResponsesModelClient(properties,
                    config.modelRestClient(RestClient.builder(), properties),
                    config.modelStreamRestClient(properties), codec, new ResponsesStreamReader(mapper, codec));
            List<String> deltas = new ArrayList<>();

            String result = client.chatStream("问题", delta -> {
                deltas.add(delta);
                deltaReceived.countDown();
            });

            assertThat(receivedBeforeCompletion).isTrue();
            assertThat(deltas).containsExactly("首段正文");
            assertThat(result).isEqualTo("首段正文");
        } finally {
            server.stop(0);
        }
    }
}
