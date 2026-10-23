package com.example.agent.client.model;

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
    private String toolChoice = "auto";
}