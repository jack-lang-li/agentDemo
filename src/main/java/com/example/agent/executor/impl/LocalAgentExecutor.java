package com.example.agent.executor.impl;

import com.example.agent.client.ModelClient;
import com.example.agent.executor.ChatExecutorCodes;
import com.example.agent.executor.StreamingChatExecutor;
import com.example.agent.service.model.ChatCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * 自建 Agent 的执行适配器，同时支持完整回答与增量输出。
 *
 * <p>第一轮重构先复用 ModelClient 中已经联调通过的实现，保持模型协议、
 * 工具执行和最大循环轮数不变。第二轮再拆分 ModelClient 的 Agent 编排与通信职责，
 * 上层依赖的执行器接口和 HTTP 接口均可保持稳定。</p>
 */
@Component
@RequiredArgsConstructor
public class LocalAgentExecutor implements StreamingChatExecutor {

    private final ModelClient modelClient;

    @Override
    public String code() {
        return ChatExecutorCodes.LOCAL_AGENT;
    }

    @Override
    public String execute(ChatCommand command) {
        // 当前模型接口只接收问题；userId 暂不用于会话隔离或持久化。
        return modelClient.chat(command.message());
    }

    @Override
    public void executeStream(
            ChatCommand command,
            Consumer<String> onDelta,
            Consumer<String> onStatus) {

        // 回调保持原样传递，连接写入失败或 Agent 执行失败都必须向上传播。
        modelClient.chatStream(command.message(), onDelta, onStatus);
    }
}
