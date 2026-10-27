package com.example.agent.client;

import com.example.agent.config.DifyProperties;
import com.example.agent.exception.DifyCallException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
public class DifyWorkflowClient {

    private static final Logger logger =
            LoggerFactory.getLogger(DifyWorkflowClient.class);

    private final RestClient restClient;

    public DifyWorkflowClient(DifyProperties properties) {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeaders(headers ->
                        headers.setBearerAuth(properties.getApiKey()))
                .build();
    }

    public String run(String question, String userId) {

        // 对应你在 Postman 中发送的 JSON。
        Map<String, Object> request = Map.of(
                "inputs", Map.of("question", question),
                "response_mode", "blocking",
                "user", userId
        );

        JsonNode response;

        try {
            response = restClient.post()
                    .uri("/workflows/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

        } catch (RestClientResponseException exception) {
            // Dify 返回了 HTTP 4xx、5xx。
            throw new DifyCallException(
                    "Dify HTTP 调用失败，状态码："
                            + exception.getStatusCode().value(),
                    exception
            );

        } catch (RestClientException exception) {
            // 网络、超时或响应转换等异常。
            throw new DifyCallException(
                    "Dify 请求失败，请检查网络、超时和服务状态",
                    exception
            );
        }

        if (response == null || !response.path("data").isObject()) {
            throw new DifyCallException(
                    "Dify 响应为空，或缺少有效的 data 对象"
            );
        }

        JsonNode data = response.path("data");

        String runId = response.path("workflow_run_id")
                .asText("unknown");

        String status = data.path("status").asText();

        logger.info(
                "Dify 工作流执行结果：runId={}, status={}, elapsedTime={}",
                runId,
                status,
                data.path("elapsed_time").asDouble()
        );

        // HTTP 200 之后，还需要检查工作流本身是否成功。
        if (!"succeeded".equals(status)) {
            logger.warn(
                    "Dify 工作流未成功完成：runId={}, status={}, error={}",
                    runId,
                    status,
                    data.path("error").asText("")
            );

            throw new DifyCallException(
                    "Dify 工作流未成功完成，runId=" + runId
            );
        }

        JsonNode answer = data.path("outputs").path("answer");

        if (!answer.isTextual() || answer.asText().isBlank()) {
            throw new DifyCallException(
                    "Dify 未返回有效的 outputs.answer，runId=" + runId
            );
        }

        return answer.asText();
    }
}