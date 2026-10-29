package com.example.agent.llm.api.model;

/**
 * 模型提出的工具调用请求，尚未执行 Java 代码。
 *
 * <p>模型输出属于待校验数据，因此解析时先保留原值；ToolCallExecutor 会统一检查
 * callId、名称、JSON 参数和本次工具集合，再调用业务工具。</p>
 *
 * @param callId 上游用于关联工具请求与结果的标识
 * @param name 请求调用的工具名称
 * @param arguments 模型生成的 JSON 参数文本
 */
public record ToolCall(String callId, String name, String arguments) {
}
