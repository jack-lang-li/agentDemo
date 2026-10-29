package com.example.agent.llm.api.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次模型请求的业务输入，不包含 URL、密钥或 HTTP/SSE 参数。
 *
 * <p>普通业务通过 text 工厂创建纯文本请求；Agent 在此基础上显式提供工具定义，
 * 并追加已经完成的模型轮次。每次续接都创建新对象，不修改其他请求的上下文。
 * tools 是模型能看到的说明，真正的 Java 工具实例只交给 AgentRunner。</p>
 *
 * @param systemPrompt 可选的业务提示词，null 或空白时不发送 system 消息
 * @param message 本次用户问题
 * @param tools 本次请求可见的工具定义，空列表表示不提供工具
 * @param turns 本次 Agent 执行已经完成的轮次，不代表跨请求会话记忆
 */
public record ModelInput(
        String systemPrompt,
        String message,
        List<ModelTool> tools,
        List<ModelTurn> turns) {

    public ModelInput {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        tools = List.copyOf(tools);
        turns = List.copyOf(turns);
    }

    /** 创建不携带任何业务工具的单次模型请求。 */
    public static ModelInput text(String message) {
        return text(null, message);
    }

    /** 创建带业务提示词的纯文本请求，提示词与用户问题使用不同的消息角色。 */
    public static ModelInput text(String systemPrompt, String message) {
        return new ModelInput(systemPrompt, message, List.of(), List.of());
    }

    /** 返回绑定了本次工具说明的新输入，调用方应为每次任务提供独立的工具定义。 */
    public ModelInput withTools(List<ModelTool> definitions) {
        return new ModelInput(systemPrompt, message, definitions, turns);
    }

    /**
     * 将完整模型输出和与其对应的工具结果作为一个轮次追加。
     * 保留完整输出能让协议适配器回传 reasoning 等必要上下文，业务层无需拼装 JSON。
     */
    public ModelInput appendTurn(ModelResult response, List<ToolResult> results) {
        List<ModelTurn> continued = new ArrayList<>(turns);
        continued.add(new ModelTurn(response, results));
        return new ModelInput(systemPrompt, message, tools, continued);
    }
}
