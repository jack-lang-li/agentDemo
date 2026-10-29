package com.example.agent.client.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一次完整模型调用的结果：可展示的正文、待执行的工具请求，以及续接所需的原始输出。
 *
 * <p>普通业务通常只读取 text；Agent 使用 toolCalls 判断是否需要继续。
 * outputItems 是协议适配器保留的上下文，不应作为前端正文展示。
 * JSON 节点在构造和读取时深拷贝，防止不同请求误改共享的历史输出。</p>
 */
public record ModelResult(String text, List<ToolCall> toolCalls, List<JsonNode> outputItems) {

    public ModelResult {
        text = text == null ? "" : text;
        toolCalls = List.copyOf(toolCalls);
        outputItems = copyItems(outputItems);
    }

    @Override
    public List<JsonNode> outputItems() {
        return copyItems(outputItems);
    }

    /** 判断模型本轮是否要求执行工具；该判断本身不会触发任何业务操作。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * 供只需要正文的业务使用，拒绝把尚未处理的工具请求当作最终答案。
     * generate/generateStream 允许返回工具请求，由 Agent 或调用业务继续处理。
     */
    public String requireText() {
        if (hasToolCalls()) {
            throw new IllegalStateException("模型返回了工具调用，需要通过 AgentRunner 或业务继续处理");
        }
        if (text.isBlank()) {
            throw new IllegalStateException("模型未返回有效回答");
        }
        return text;
    }

    private static List<JsonNode> copyItems(List<JsonNode> items) {
        return items.stream().map(item -> (JsonNode) item.deepCopy()).toList();
    }
}
