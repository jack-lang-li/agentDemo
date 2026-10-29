package com.example.agent.llm.provider.responses;

import com.example.agent.llm.provider.responses.protocol.enums.ContentItemType;
import com.example.agent.llm.provider.responses.protocol.enums.MessageRole;
import com.example.agent.llm.provider.responses.protocol.enums.OutputItemType;
import com.example.agent.llm.provider.responses.protocol.enums.ResponseStatus;
import com.example.agent.llm.provider.responses.protocol.enums.ToolChoice;
import com.example.agent.llm.provider.responses.protocol.ContentItem;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.provider.responses.protocol.ModelMessage;
import com.example.agent.llm.provider.responses.protocol.ModelRequest;
import com.example.agent.llm.provider.responses.protocol.ModelResponse;
import com.example.agent.llm.api.model.ModelResult;
import com.example.agent.llm.api.model.ModelTurn;
import com.example.agent.llm.provider.responses.protocol.OutputItem;
import com.example.agent.llm.api.model.ToolCall;
import com.example.agent.llm.api.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Responses 协议编解码边界，集中维护平台字段与公共模型对象之间的转换。
 *
 * <p>HTTP 客户端只负责传输；AgentRunner 只处理 ModelResult、ToolCall 和 ToolResult。
 * 完整 output 原样作为续接上下文保留，不能只保留回答和函数调用而丢弃 reasoning 等项。</p>
 */
@RequiredArgsConstructor
public class ResponsesModelCodec {

    private final ObjectMapper objectMapper;

    /** 将一次业务输入编码为 Responses 请求，同步与流式使用完全相同的历史编码。 */
    public ModelRequest encode(ModelInput input, String model, boolean streaming) {
        List<JsonNode> history = new ArrayList<>();
        if (input.systemPrompt() != null && !input.systemPrompt().isBlank()) {
            history.add(objectMapper.valueToTree(
                    new ModelMessage(MessageRole.SYSTEM.getValue(), input.systemPrompt())));
        }
        history.add(objectMapper.valueToTree(new ModelMessage(MessageRole.USER.getValue(), input.message())));

        for (ModelTurn turn : input.turns()) {
            history.addAll(turn.response().outputItems());
            for (ToolResult result : turn.toolResults()) {
                history.add(objectMapper.valueToTree(Map.of(
                        "type", OutputItemType.FUNCTION_CALL_OUTPUT.getValue(),
                        "call_id", result.callId(),
                        "output", result.output()
                )));
            }
        }

        ModelRequest request = new ModelRequest();
        request.setModel(model);
        request.setInput(history);
        request.setTools(input.tools());
        request.setToolChoice(input.tools().isEmpty() ? null : ToolChoice.AUTO.getValue());
        request.setStream(streaming ? true : null);
        return request;
    }

    /**
     * 统一校验完整响应并提取可复用结果，同步响应和 response.completed 共用此方法。
     * 工具参数先保留原始文本，由工具执行组件统一校验，解码过程绝不触发业务操作。
     */
    public ModelResult decode(JsonNode rawResponse) {
        if (rawResponse == null || !rawResponse.path("output").isArray()) {
            throw new IllegalStateException("模型响应缺少有效的output数组");
        }
        String status = rawResponse.path("status").asText();
        if (!ResponseStatus.COMPLETED.getValue().equals(status)) {
            throw new IllegalStateException("模型响应未完成，status=" + status);
        }

        ModelResponse response = objectMapper.convertValue(rawResponse, ModelResponse.class);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (OutputItem item : response.getOutput()) {
            if (item == null) {
                throw new IllegalStateException("模型output数组包含空元素");
            }
            if (OutputItemType.FUNCTION_CALL.getValue().equals(item.getType())) {
                calls.add(new ToolCall(item.getCallId(), item.getName(), item.getArguments()));
            } else if (OutputItemType.MESSAGE.getValue().equals(item.getType()) && item.getContent() != null) {
                for (ContentItem content : item.getContent()) {
                    if (ContentItemType.OUTPUT_TEXT.getValue().equals(content.getType()) && content.getText() != null) {
                        text.append(content.getText());
                    } else if (ContentItemType.REFUSAL.getValue().equals(content.getType())
                            && content.getRefusal() != null) {
                        text.append(content.getRefusal());
                    }
                }
            }
        }

        List<JsonNode> outputItems = new ArrayList<>();
        rawResponse.path("output").forEach(outputItems::add);
        return new ModelResult(text.toString(), calls, outputItems);
    }
}
