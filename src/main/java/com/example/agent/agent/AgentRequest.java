package com.example.agent.agent;

import com.example.agent.tool.AgentTool;

import java.util.List;

/**
 * 业务提交给通用 Agent 的一次执行任务。
 *
 * <p>业务负责提供提示词和工具实例，工具负责说明名称、用途、参数及执行逻辑。
 * AgentRunner 不自动收集 Spring 中的全部工具，也不替业务决定应开放哪些能力。
 * 工具列表按请求复制，工具实现自身应保持无状态，或由业务为本次请求单独创建。</p>
 *
 * @param systemPrompt 可选业务提示词
 * @param message 用户问题
 * @param tools 本次任务允许使用的 Java 工具，允许为空列表
 * @param maxModelRounds 最大模型请求轮数，包含最终回答那一轮
 */
public record AgentRequest(
        String systemPrompt,
        String message,
        List<AgentTool> tools,
        int maxModelRounds) {

    public static final int DEFAULT_MAX_MODEL_ROUNDS = 5;

    public AgentRequest {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (maxModelRounds < 1) {
            throw new IllegalArgumentException("模型调用轮数必须大于 0");
        }
        tools = List.copyOf(tools);
    }

    public AgentRequest(String message, List<AgentTool> tools) {
        this(null, message, tools, DEFAULT_MAX_MODEL_ROUNDS);
    }

    public AgentRequest(String systemPrompt, String message, List<AgentTool> tools) {
        this(systemPrompt, message, tools, DEFAULT_MAX_MODEL_ROUNDS);
    }
}
