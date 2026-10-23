package com.example.agent.client;

import com.example.agent.client.model.ModelMessage;
import com.example.agent.client.model.ModelRequest;
import com.example.agent.config.ModelProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 单独隔离模型交互，避免 Controller 和 Service 依赖具体模型的通信细节。
 * 先用 Mock 验证链路，无需 API Key、网络或费用。
 * 阶段 2 在这里学习 HTTP 调用；阶段 3 再抽象 ModelProvider，支持多模型。
 */
@Component
@RequiredArgsConstructor
public class ModelClient {

    private final ModelProperties modelProperties;

    public String chat(String message) {

        // 1. 将用户输入转换成模型消息
        ModelMessage userMessage =
                new ModelMessage("user", message);

        // 2. 构造发送给模型服务的请求对象
        ModelRequest request = new ModelRequest();
        request.setModel(modelProperties.getModel());
        request.setInput(List.of(userMessage));

        // 3. 创建 HTTP 客户端
        RestClient restClient = RestClient.create();

        // 4. 调用真实模型 HTTP API
        // 当前阶段故意直接接收原始 JSON 字符串，
        // 下一阶段再分析并反序列化模型响应。
        String rawResponse = restClient
                .post()
                .uri(modelProperties.getBaseUrl() + "/v1/responses")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + modelProperties.getApiKey()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);

        return rawResponse;
    }
}
