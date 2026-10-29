package com.example.agent.llm.api.model;

import java.util.List;
import java.util.Objects;

/**
 * 已完成的一次模型响应及其工具执行结果。
 * 协议适配器负责将它编码成上游需要的历史消息；Agent 不接触协议字段名称。
 */
public record ModelTurn(ModelResult response, List<ToolResult> toolResults) {

    public ModelTurn {
        Objects.requireNonNull(response, "模型响应不能为空");
        toolResults = List.copyOf(toolResults);
    }
}
