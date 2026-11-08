package com.example.agent.business.security.authorization;

import com.example.agent.agent.tool.AgentTool;
import com.example.agent.llm.api.model.ModelTool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;

/**
 * 当前请求专用的工具授权包装器。
 *
 * 名称和模型定义委托原工具，执行前检查本次授权快照。
 * 每次请求单独创建，不注册为 Spring 单例组件。
 */
public final class AuthorizedAgentTool implements AgentTool {

    private final CallerAwareAgentTool delegate;
    private final CallerAuthorization authorization;
    private final String requiredPermission;

    public AuthorizedAgentTool(
            CallerAwareAgentTool delegate,
            CallerAuthorization authorization,
            String requiredPermission) {

        this.delegate = Objects.requireNonNull(
                delegate, "delegate 不能为空"
        );

        this.authorization = Objects.requireNonNull(
                authorization, "authorization 不能为空"
        );

        if (requiredPermission == null
                || requiredPermission.isBlank()
                || !requiredPermission.equals(requiredPermission.strip())) {

            throw new IllegalArgumentException(
                    "requiredPermission 不能为空或包含首尾空白"
            );
        }

        this.requiredPermission = requiredPermission;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public ModelTool definition() {
        return delegate.definition();
    }

    @Override
    public Object execute(JsonNode arguments) {
        // 授权通过后，才允许进入原工具的业务操作。
        authorization.requirePermission(requiredPermission);
        return delegate.execute(arguments, authorization);
    }
}