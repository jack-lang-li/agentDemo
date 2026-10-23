package com.example.agent.client;

import com.example.agent.client.enums.ContentItemType;
import com.example.agent.client.enums.JsonSchemaType;
import com.example.agent.client.enums.MessageRole;
import com.example.agent.client.enums.OutputItemType;
import com.example.agent.client.enums.ResponseStatus;
import com.example.agent.client.enums.ToolChoice;
import com.example.agent.client.enums.ToolName;
import com.example.agent.client.enums.ToolType;
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

    private static final int MAX_MODEL_ROUNDS = 5;

    public String chat(String message) {

        // 本次用户请求的上下文
        List<JsonNode> history = new ArrayList<>();
        history.add(objectMapper.valueToTree(
                new ModelMessage(MessageRole.USER.getValue(), message)
        ));

        ModelRequest request = new ModelRequest();
        request.setModel(modelProperties.getModel());
        request.setInput(history);
        request.setTools(List.of(buildOrderTool()));
        request.setToolChoice(ToolChoice.AUTO.getValue());

        for (int round = 1; round <= MAX_MODEL_ROUNDS; round++) {

            logger.info("Agent开始第{}轮模型请求", round);

            // 1. 请求模型
            JsonNode rawResponse = callModel(request);

            // 2. 只处理已经完整结束的模型响应
            String status = rawResponse.path("status").asText();
            if (!ResponseStatus.COMPLETED.getValue().equals(status)) {
                throw new IllegalStateException(
                        "模型响应未完成，status=" + status
                );
            }

            ModelResponse response = objectMapper.convertValue(
                    rawResponse, ModelResponse.class
            );

            // 3. 没有工具调用：尝试取得最终回答
            if (!hasToolCalls(response)) {

                String answer = extractAnswer(response);

                if (answer == null || answer.isBlank()) {
                    throw new IllegalStateException(
                            "模型未返回工具调用，也未返回有效回答"
                    );
                }

                logger.info("Agent在第{}轮完成任务", round);
                return answer;
            }

            // 4. 已无下一轮可用于回传结果，不再执行工具
            if (round == MAX_MODEL_ROUNDS) {
                break;
            }

            // 5. 保留本轮完整的模型输出
            for (JsonNode item : rawResponse.path("output")) {
                history.add(item);
            }

            // 6. 执行本轮工具，追加执行结果
            List<JsonNode> toolResults = executeToolCalls(response);
            history.addAll(toolResults);

            logger.info(
                    "Agent第{}轮执行了{}个工具调用，继续下一轮",
                    round, toolResults.size()
            );
        }

        throw new IllegalStateException(
                "达到模型调用轮数上限，任务仍未完成："
                        + MAX_MODEL_ROUNDS
        );
    }

    private String extractAnswer(ModelResponse response) {

        for (OutputItem outputItem : response.getOutput()) {

            if (!OutputItemType.MESSAGE.getValue().equals(outputItem.getType())) {
                continue;
            }

            for (ContentItem contentItem : outputItem.getContent()) {

                if (ContentItemType.OUTPUT_TEXT.getValue().equals(contentItem.getType())) {
                    return contentItem.getText();
                }
            }
        }

        return null;
    }

    private ModelTool buildOrderTool() {

        Map<String, Object> orderIdSchema = Map.of(
                "type", JsonSchemaType.STRING.getValue(),
                "description", "需要查询的订单编号"
        );

        Map<String, Object> parameters = Map.of(
                "type", JsonSchemaType.OBJECT.getValue(),
                "properties", Map.of(
                        "orderId", orderIdSchema
                ),
                "required", List.of("orderId"),
                "additionalProperties", false
        );

        ModelTool tool = new ModelTool();
        tool.setType(ToolType.FUNCTION.getValue());
        tool.setName(ToolName.QUERY_ORDER.getValue());
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

            if (!OutputItemType.FUNCTION_CALL.getValue().equals(item.getType())) {
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

            if (!OutputItemType.FUNCTION_CALL.getValue().equals(item.getType())) {
                continue;
            }

            // 1. 只允许执行程序明确支持的工具
            if (!ToolName.QUERY_ORDER.getValue().equals(item.getName())) {
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
                    "type", OutputItemType.FUNCTION_CALL_OUTPUT.getValue(),
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

    private boolean hasToolCalls(ModelResponse response) {

        if (response.getOutput() == null) {
            return false;
        }

        for (OutputItem item : response.getOutput()) {
            if (OutputItemType.FUNCTION_CALL.getValue().equals(item.getType())) {
                return true;
            }
        }

        return false;
    }
}
