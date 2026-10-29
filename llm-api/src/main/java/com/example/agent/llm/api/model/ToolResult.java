package com.example.agent.llm.api.model;

/**
 * 一个工具执行完成后的结果，供下一轮模型请求使用。
 *
 * @param callId 对应工具调用的标识，必须与模型提出的 callId 保持一致
 * @param output 已序列化的 JSON 文本；协议适配器将其作为结果字符串回传
 */
public record ToolResult(String callId, String output) {
}
