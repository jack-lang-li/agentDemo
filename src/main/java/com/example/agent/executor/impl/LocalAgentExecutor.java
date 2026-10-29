package com.example.agent.executor.impl;

import com.example.agent.agent.AgentRequest;
import com.example.agent.agent.AgentRunner;
import com.example.agent.executor.ChatExecutorCodes;
import com.example.agent.executor.StreamingChatExecutor;
import com.example.agent.service.model.ChatCommand;
import com.example.agent.tool.LogisticsTool;
import com.example.agent.tool.OrderTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 自建 Agent 的执行适配器，同时支持完整回答与增量输出。
 *
 * <p>当前演示场景需要订单和物流工具，因此由这个业务适配器显式提供这两个工具。
 * 其他业务可提供自己的 AgentRequest；公共模型客户端和 Agent 循环不需要增加业务分支。</p>
 */
@Component
@RequiredArgsConstructor
public class LocalAgentExecutor implements StreamingChatExecutor {

    private final AgentRunner agentRunner;
    private final OrderTool orderTool;
    private final LogisticsTool logisticsTool;

    @Override
    public String code() {
        return ChatExecutorCodes.LOCAL_AGENT;
    }

    @Override
    public String execute(ChatCommand command) {
        return agentRunner.run(createRequest(command));
    }

    @Override
    public void executeStream(
            ChatCommand command,
            Consumer<String> onDelta,
            Consumer<String> onStatus) {

        // 回调保持原样传递，连接写入失败或 Agent 执行失败都必须向上传播。
        agentRunner.runStream(createRequest(command), onDelta, onStatus);
    }

    private AgentRequest createRequest(ChatCommand command) {
        // 工具名称、用途、参数和实际执行分别由两个业务工具实现提供。
        // 当前工具使用模拟数据；以后需要用户相关操作时，由业务工具执行真实权限校验。
        return new AgentRequest(command.message(), List.of(orderTool, logisticsTool));
    }
}
