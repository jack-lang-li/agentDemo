package com.example.agent.client;

import com.example.agent.client.model.*;
import com.example.agent.config.ModelProperties;
import com.example.agent.tool.OrderTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单独隔离模型交互，避免 Controller 和 Service 依赖具体模型的通信细节。
 * 先用 Mock 验证链路，无需 API Key、网络或费用。
 * 阶段 2 在这里学习 HTTP 调用；阶段 3 再抽象 ModelProvider，支持多模型。
 */
@Component
@RequiredArgsConstructor
public class ModelClient {

    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);

    private final ModelProperties modelProperties;
    private final RestClient modelRestClient;
    private final OrderTool orderTool;
    private final ObjectMapper objectMapper;

    public String chat(String message) {

        // 1. 初始上下文：用户问题
        List<JsonNode> history = new ArrayList<>();
        history.add(objectMapper.valueToTree(
                new ModelMessage("user", message)
        ));

        ModelRequest request = new ModelRequest();
        request.setModel(modelProperties.getModel());
        request.setInput(history);
        request.setTools(List.of(buildOrderTool()));

        // 2. 第一次请求：模型决定是否调用工具
        JsonNode firstRaw = callModel(request);
        ModelResponse firstResponse = objectMapper.convertValue(
                firstRaw, ModelResponse.class
        );

        // 3. 执行模型请求的工具，收集结果
        List<JsonNode> toolResults = executeToolCalls(firstResponse);

        // 没有工具调用：直接返回本次回答
        if (toolResults.isEmpty()) {
            return extractAnswer(firstResponse);
        }

        // 4. 保留第一次模型输出，再追加工具结果
        for (JsonNode item : firstRaw.path("output")) {
            history.add(item);
        }
        history.addAll(toolResults);

        // 5. 本节限定一轮工具执行，第二次让模型生成回答
        request.setToolChoice("none");

        JsonNode secondRaw = callModel(request);
        ModelResponse secondResponse = objectMapper.convertValue(
                secondRaw, ModelResponse.class
        );

        // 6. 提取第二次模型响应中的回答
        return extractAnswer(secondResponse);
    }

    private String extractAnswer(ModelResponse response) {

        for (OutputItem outputItem : response.getOutput()) {

            if (!"message".equals(outputItem.getType())) {
                continue;
            }

            for (ContentItem contentItem : outputItem.getContent()) {

                if ("output_text".equals(contentItem.getType())) {
                    return contentItem.getText();
                }
            }
        }

        return null;
    }

    private ModelTool buildOrderTool() {

        Map<String, Object> orderIdSchema = Map.of(
                "type", "string",
                "description", "需要查询的订单编号"
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "orderId", orderIdSchema
                ),
                "required", List.of("orderId"),
                "additionalProperties", false
        );

        ModelTool tool = new ModelTool();
        tool.setType("function");
        tool.setName("query_order");
        tool.setDescription(
                "根据订单编号查询订单当前状态。当用户需要查询具体订单状态时使用。"
        );
        tool.setParameters(parameters);

        return tool;
    }

    private void inspectToolCalls(ModelResponse response) {

        if (response == null || response.getOutput() == null) {
            return;
        }

        for (OutputItem item : response.getOutput()) {

            if (!"function_call".equals(item.getType())) {
                continue;
            }
            logger.info("工具名称：{}", item.getName());
            logger.info("调用参数：{}", item.getArguments());
            logger.info("调用编号：{}", item.getCallId());
        }
    }
    private List<JsonNode> executeToolCalls(ModelResponse response) {
        List<JsonNode> results = new ArrayList<>();

        if (response == null || response.getOutput() == null) {
            return results;
        }

        for (OutputItem item : response.getOutput()) {

            if (!"function_call".equals(item.getType())) {
                continue;
            }

            // 1. 只允许执行程序明确支持的工具
            if (!"query_order".equals(item.getName())) {
                throw new IllegalArgumentException(
                        "不支持的工具：" + item.getName()
                );
            }

            if (item.getCallId() == null || item.getCallId().isBlank()) {
                throw new IllegalArgumentException("工具调用缺少call_id");
            }

            // 2. 解析模型提供的参数字符串
            if (item.getArguments() == null) {
                throw new IllegalArgumentException("工具调用缺少参数");
            }

            JsonNode arguments;
            try {
                arguments = objectMapper.readTree(item.getArguments());
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "工具参数不是合法JSON", exception
                );
            }

            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("工具参数必须是JSON对象");
            }

            // 3. 检查订单编号
            JsonNode orderIdNode = arguments.get("orderId");
            if (orderIdNode == null
                    || !orderIdNode.isTextual()
                    || orderIdNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "orderId必须是非空字符串"
                );
            }

            String orderId = orderIdNode.asText();

            // 4. 真正执行Java工具
            String result = orderTool.queryOrder(orderId);

            results.add(objectMapper.valueToTree(Map.of(
                    "type", "function_call_output",
                    "call_id", item.getCallId(),
                    "output", result
            )));
            logger.info(
                    "工具执行完成：name={}, callId={}, result={}",
                    item.getName(), item.getCallId(), result
            );
        }
        return results;
    }

    private JsonNode callModel(ModelRequest request) {

        try {
            JsonNode response = modelRestClient
                    .post()
                    .uri(modelProperties.getBaseUrl() + "/v1/responses")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + modelProperties.getApiKey()
                    )
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null || !response.path("output").isArray()) {
                throw new IllegalStateException("模型响应缺少有效的output数组");
            }

            return response;

        } catch (RestClientException exception) {
            logger.error(
                    "模型调用失败：model={}",
                    modelProperties.getModel(),
                    exception
            );
            throw exception;
        }
    }
}
