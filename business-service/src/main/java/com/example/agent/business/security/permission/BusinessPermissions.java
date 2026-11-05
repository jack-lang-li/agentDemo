package com.example.agent.business.security.permission;

/**
 * 业务服务的权限码。
 *
 * 权限码表达用户可以执行的业务操作。
 * 后续权限入库时，数据库保存相同的字符串，
 * HTTP 授权规则继续引用这里的常量。
 */
public final class BusinessPermissions {

    /**
     * 调用 Agent 聊天入口。
     *
     * 此权限只控制聊天入口的访问资格。
     * Agent 内部各个工具的权限将在后续阶段单独处理。
     */
    public static final String CHAT_AGENT_INVOKE =
            "chat:agent:invoke";

    /**
     * 调用工作流聊天入口。
     *
     * 当前接口由 Dify 提供工作流能力。
     * 使用业务能力命名，便于后续替换工作流实现。
     */
    public static final String CHAT_WORKFLOW_INVOKE =
            "chat:workflow:invoke";

    private BusinessPermissions() {
    }
}
