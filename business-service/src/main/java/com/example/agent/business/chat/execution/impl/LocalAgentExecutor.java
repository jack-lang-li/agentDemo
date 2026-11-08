package com.example.agent.business.chat.execution.impl;

import com.example.agent.agent.AgentRequest;
import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.tool.AgentTool;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.execution.StreamingChatExecutor;
import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.logistics.tool.LogisticsTool;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.business.security.authorization.AuthorizedAgentTool;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.business.security.permission.BusinessPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 自建 Agent 的执行适配器，同时支持完整回答与增量输出。
 *
 * <p>根据本次调用的权限选择订单、物流工具，
 *  * 并通过授权包装器执行。
 *  * 同步与流式执行共用相同的工具选择逻辑。
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
        CallerAuthorization authorization = command.authorization();

        List<AgentTool> tools = new ArrayList<>();

        if (authorization.hasPermission(BusinessPermissions.ORDER_QUERY)) {
            tools.add(new AuthorizedAgentTool(
                    orderTool,
                    authorization,
                    BusinessPermissions.ORDER_QUERY
            ));
        }

        if (authorization.hasPermission(BusinessPermissions.LOGISTICS_QUERY)) {
            tools.add(new AuthorizedAgentTool(
                    logisticsTool,
                    authorization,
                    BusinessPermissions.LOGISTICS_QUERY
            ));
        }

        // 空列表仍可普通聊天。
        // 模型只能看到本次选择的工具，包装器在执行时再次校验权限。
        return new AgentRequest(command.message(), tools);
    }
}
