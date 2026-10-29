package com.example.agent.testsupport;

import com.example.agent.llm.api.model.ModelResult;
import com.example.agent.llm.api.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** 独立构造协议样例，测试不通过生产解码器生成预期结果。 */
public final class ModelFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelFixtures() {
    }

    public static ModelResult answer(String text) {
        ObjectNode item = MAPPER.createObjectNode().put("type", "message");
        item.putArray("content").addObject().put("type", "output_text").put("text", text);
        return new ModelResult(text, List.of(), List.of(item));
    }

    public static ModelResult proposal(String callId, String name, String arguments) {
        ObjectNode item = MAPPER.createObjectNode().put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments);
        return new ModelResult("", List.of(new ToolCall(callId, name, arguments)), List.of(item));
    }

    public static String responseJson(ModelResult result) {
        ObjectNode response = MAPPER.createObjectNode().put("status", "completed");
        response.set("output", MAPPER.valueToTree(result.outputItems()));
        return response.toString();
    }

    public static String completedFrame(ModelResult result) {
        return "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":"
                + responseJson(result) + "}\n\n";
    }
}
