package com.example.agent.business.chat.execution.impl;

import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.business.chat.execution.ChatExecutor;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.application.model.ChatCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 已发布 Dify Workflow 的执行适配器。
 *
 * <p>业务参数到 Dify inputs、user 的具体映射以及响应校验继续由客户端负责。
 * 当前客户端采用 blocking 模式，因此这里只声明普通聊天能力。
 * DifyCallException 原样传播，使现有全局异常处理仍能返回 HTTP 502。</p>
 */
@Component
@RequiredArgsConstructor
public class DifyWorkflowExecutor implements ChatExecutor {

    private final DifyWorkflowClient difyWorkflowClient;

    @Override
    public String code() {
        return ChatExecutorCodes.DIFY_WORKFLOW;
    }

    @Override
    public String execute(ChatCommand command) {
        // 用户标识由上层显式传入，策略和客户端不再自行写死测试用户。
        return difyWorkflowClient.run(command.message(), command.userId());
    }
}
