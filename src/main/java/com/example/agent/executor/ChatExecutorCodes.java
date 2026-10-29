package com.example.agent.executor;

/**
 * 现有 HTTP 入口与内置执行策略共同使用的业务编码。
 *
 * <p>集中声明可以避免入口和实现分别手写字符串而产生拼写差异。
 * 这里只记录内置编码，不限制其他实现使用新的唯一编码。</p>
 */
public final class ChatExecutorCodes {

    /** 使用项目自身的模型调用、工具执行和 Agent 自动循环。 */
    public static final String LOCAL_AGENT = "local-agent";

    /** 调用已发布的 Dify Workflow，目前以 blocking 模式获取完整回答。 */
    public static final String DIFY_WORKFLOW = "dify-workflow";

    private ChatExecutorCodes() {
        // 常量类无需创建实例。
    }
}
