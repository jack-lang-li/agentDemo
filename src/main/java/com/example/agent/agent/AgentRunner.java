package com.example.agent.agent;

import java.util.function.Consumer;

/**
 * 可供不同业务复用的 Agent 自动循环接口。
 *
 * <p>只需要一次模型生成时直接使用 ModelClient；需要工具执行及多轮回传时使用此接口。
 * 每次运行的上下文、工具集合和轮数限制来自 AgentRequest，不依赖 HTTP 或具体业务。</p>
 */
public interface AgentRunner {

    /** 执行模型与工具循环，返回最终完整回答；任务失败直接抛出异常。 */
    String run(AgentRequest request);

    /**
     * 在当前线程中执行同样的循环，通过回调输出正文和状态。
     * 正常返回表示整个任务成功，执行异常和回调异常均向上传播。
     * SSE 的 done/error 事件仍由 HTTP 入口管理。
     */
    void runStream(AgentRequest request, Consumer<String> onDelta, Consumer<String> onStatus);
}
