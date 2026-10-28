package com.example.agent.client.model;

import com.example.agent.client.enums.ToolChoice;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
public class ModelRequest {

    /**
     * 使用哪个大模型
     */
    private String model;

    /**
     * 当前发送给模型的上下文消息
     */
//    private List<ModelMessage> input;

    private List<JsonNode> input;

    private List<ModelTool> tools;
    /**
     * 本次请求提供给模型的工具定义
     */
    @JsonProperty("tool_choice")
    private String toolChoice = ToolChoice.AUTO.getValue();

    /**
     * true：模型以 SSE 形式返回增量事件。
     * null：不发送该字段，兼容原有同步调用。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean stream;
}
