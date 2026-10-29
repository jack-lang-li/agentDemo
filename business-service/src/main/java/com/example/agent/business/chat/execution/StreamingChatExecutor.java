package com.example.agent.business.chat.execution;

import com.example.agent.business.chat.application.model.ChatCommand;

import java.util.function.Consumer;

/**
 * 在普通聊天契约上增加真正的流式输出能力。
 *
 * <p>仅支持阻塞调用的平台只实现 ChatExecutor；不能先取得完整回答再切成字符，
 * 假装支持流式生成。注册表通过这个接口判断策略是否具备流式能力。</p>
 */
public interface StreamingChatExecutor extends ChatExecutor {

    /**
     * 在当前调用线程中执行任务，按顺序发送正文增量和状态通知。
     *
     * <p>正常返回表示整个任务已完成；生成失败或回调失败必须向上传播异常。
     * 不应自行启动后台任务后提前返回，否则 HTTP 层会过早发送 done。
     * 具体 SSE 事件名称、JSON 结构及连接生命周期均由 HTTP 层管理。</p>
     *
     * @param command 本次执行参数
     * @param onDelta 新生成的正文片段，可被多次调用
     * @param onStatus 执行进度，例如模型请求轮次或工具执行状态
     */
    void executeStream(
            ChatCommand command,
            Consumer<String> onDelta,
            Consumer<String> onStatus
    );
}
