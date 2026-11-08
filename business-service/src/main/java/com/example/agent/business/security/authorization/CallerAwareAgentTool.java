package com.example.agent.business.security.authorization;

import com.example.agent.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 需要可信调用者上下文的业务工具。
 *
 * 身份由服务端包装器传入，
 * 不从模型参数或工作线程读取。
 */
public interface CallerAwareAgentTool extends AgentTool {

    Object execute(
            JsonNode arguments,
            CallerAuthorization authorization);

    @Override
    default Object execute(JsonNode arguments) {
        throw new IllegalStateException(
                "业务工具尚未绑定服务端授权上下文"
        );
    }
}