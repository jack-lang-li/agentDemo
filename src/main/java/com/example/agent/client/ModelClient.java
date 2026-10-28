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
import com.example.agent.tool.AgentTool;
import com.example.agent.tool.LogisticsTool;
import com.example.agent.tool.OrderTool;
import com.example.agent.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

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
        request.setTools(toolRegistry.definitions());
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

    private List<JsonNode> executeToolCalls(ModelResponse response) {

        List<JsonNode> results = new ArrayList<>();

        if (response == null || response.getOutput() == null) {
            return results;
        }

        for (OutputItem item : response.getOutput()) {

            if (!OutputItemType.FUNCTION_CALL.getValue()
                    .equals(item.getType())) {
                continue;
            }

            if (item.getCallId() == null || item.getCallId().isBlank()) {
                throw new IllegalArgumentException("工具调用缺少call_id");
            }

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

            // 根据工具名称，读取对应参数并执行
            AgentTool tool = toolRegistry.getRequired(item.getName());

            Map<String, Object> result = tool.execute(arguments);

            String resultJson;
            try {
                resultJson = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException(
                        "工具结果序列化失败", exception
                );
            }

            results.add(objectMapper.valueToTree(Map.of(
                    "type", OutputItemType.FUNCTION_CALL_OUTPUT.getValue(),
                    "call_id", item.getCallId(),
                    "output", resultJson
            )));

            logger.info(
                    "工具执行完成：name={}, arguments={}, callId={}, result={}",
                    item.getName(), item.getArguments(),
                    item.getCallId(), resultJson
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

    public void chatStream(
            String message,
            Consumer<String> onDelta,
            Consumer<String> onStatus) {

        List<JsonNode> history = new ArrayList<>();

        history.add(objectMapper.valueToTree(
                new ModelMessage(
                        MessageRole.USER.getValue(),
                        message
                )
        ));

        ModelRequest request = new ModelRequest();
        request.setModel(modelProperties.getModel());
        request.setInput(history);
        request.setTools(toolRegistry.definitions());
        request.setToolChoice(ToolChoice.AUTO.getValue());
        request.setStream(true);

        RestClient streamClient = createStreamClient();

        for (int round = 1; round <= MAX_MODEL_ROUNDS; round++) {

            onStatus.accept("正在请求模型，第 " + round + " 轮");

            AtomicBoolean receivedText = new AtomicBoolean(false);

            JsonNode rawResponse = callModelStream(
                    streamClient,
                    request,
                    delta -> {
                        if (!delta.isBlank()) {
                            receivedText.set(true);
                        }

                        onDelta.accept(delta);
                    }
            );

            ModelResponse response = objectMapper.convertValue(
                    rawResponse,
                    ModelResponse.class
            );

            // 本轮没有工具调用：Agent 可以结束。
            if (!hasToolCalls(response)) {

                if (!receivedText.get()) {
                    throw new IllegalStateException(
                            "模型未返回可展示的正文"
                    );
                }

                logger.info(
                        "流式 Agent 在第 {} 轮完成任务",
                        round
                );

                return;
            }

            // 最后一轮不再执行工具，保持原来的轮数限制。
            if (round == MAX_MODEL_ROUNDS) {
                break;
            }

            // 保存完整模型输出，包括工具调用等上下文。
            for (JsonNode item : rawResponse.path("output")) {
                history.add(item);
            }

            onStatus.accept("正在执行工具");

            List<JsonNode> toolResults =
                    executeToolCalls(response);

            history.addAll(toolResults);

            onStatus.accept("工具执行完成，正在继续生成回答");
        }

        throw new IllegalStateException(
                "达到模型调用轮数上限，任务仍未完成："
                        + MAX_MODEL_ROUNDS
        );
    }

    private RestClient createStreamClient() {

        SimpleClientHttpRequestFactory factory =
                new SimpleClientHttpRequestFactory();

        factory.setConnectTimeout(10_000);

        // 等待上游数据的读取超时。
        factory.setReadTimeout(90_000);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private JsonNode callModelStream(
            RestClient streamClient,
            ModelRequest request,
            Consumer<String> onDelta) {

        return streamClient.post()
                .uri(
                        modelProperties.getBaseUrl()
                                + "/v1/responses"
                )
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + modelProperties.getApiKey()
                )
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(request)
                .exchange((httpRequest, httpResponse) -> {

                    if (!httpResponse.getStatusCode()
                            .is2xxSuccessful()) {

                        throw new IllegalStateException(
                                "模型流式请求失败，HTTP 状态码："
                                        + httpResponse
                                        .getStatusCode()
                                        .value()
                        );
                    }

                    MediaType contentType =
                            httpResponse.getHeaders()
                                    .getContentType();

                    if (contentType == null
                            || !MediaType.TEXT_EVENT_STREAM
                            .isCompatibleWith(contentType)) {

                        throw new IllegalStateException(
                                "模型未返回 text/event-stream，"
                                        + "请检查流式接口支持情况"
                        );
                    }

                    return readModelStream(
                            httpResponse.getBody(),
                            onDelta
                    );
                });
    }

    private JsonNode readModelStream(
            InputStream inputStream,
            Consumer<String> onDelta) throws IOException {

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        inputStream,
                        StandardCharsets.UTF_8
                ))) {

            String eventName = "";
            StringBuilder data = new StringBuilder();

            String line;

            while ((line = reader.readLine()) != null) {

                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("流式请求已中断");
                }

                // 空行表示一个 SSE 事件结束。
                if (line.isEmpty()) {

                    if (data.length() > 0) {
                        JsonNode completed = consumeModelEvent(
                                eventName,
                                data.toString(),
                                onDelta
                        );

                        if (completed != null) {
                            return completed;
                        }
                    }

                    eventName = "";
                    data.setLength(0);
                    continue;
                }

                // SSE 注释或心跳。
                if (line.startsWith(":")) {
                    continue;
                }

                if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();

                } else if (line.startsWith("data:")) {

                    String value = line.substring(5);

                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }

                    if (data.length() > 0) {
                        data.append('\n');
                    }

                    data.append(value);
                }
            }

            // 兼容最后一个事件没有以空行结尾的情况。
            if (data.length() > 0) {
                JsonNode completed = consumeModelEvent(
                        eventName,
                        data.toString(),
                        onDelta
                );

                if (completed != null) {
                    return completed;
                }
            }
        }

        throw new IllegalStateException(
                "模型连接已结束，但没有收到 response.completed"
        );
    }

    private JsonNode consumeModelEvent(
            String eventName,
            String data,
            Consumer<String> onDelta) throws IOException {

        if (data.isBlank()) {
            return null;
        }

        // Responses 的成功依据是 response.completed。
        if ("[DONE]".equals(data)) {
            return null;
        }

        JsonNode event = objectMapper.readTree(data);

        if (event == null || !event.isObject()) {
            throw new IllegalStateException(
                    "模型返回了无效的 SSE 数据"
            );
        }

        String type = event.path("type").asText(eventName);

        switch (type) {

            case "response.output_text.delta",
                 "response.refusal.delta" -> {

                JsonNode delta = event.path("delta");

                if (!delta.isTextual()) {
                    throw new IllegalStateException(
                            "模型文本增量不是字符串"
                    );
                }

                if (!delta.asText().isEmpty()) {
                    onDelta.accept(delta.asText());
                }
            }

            case "response.completed" -> {

                JsonNode response = event.path("response");

                if (!response.isObject()
                        || !response.path("output").isArray()
                        || !ResponseStatus.COMPLETED.getValue()
                        .equals(response.path("status").asText())) {

                    throw new IllegalStateException(
                            "完成事件缺少有效的完整模型响应"
                    );
                }

                return response;
            }

            case "response.failed",
                 "response.incomplete",
                 "error" -> {

                throw new IllegalStateException(
                        "模型流式响应未成功完成，事件：" + type
                );
            }

            default -> {
                // 暂不向前端转发其他事件。
                // 工具参数在完整 response 中取得后再执行。
            }
        }

        return null;
    }
}
