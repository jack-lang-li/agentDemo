package com.example.agent.service.model;

/**
 * 一次聊天执行所需的业务参数，与 HTTP 请求 DTO 分开维护。
 *
 * <p>record 的字段不可重新赋值，每个请求创建自己的实例；它不保存跨请求的会话历史。
 * userId 由服务端填入，目前沿用测试用户，后续接入登录系统时从认证上下文获取。
 * 该字段用于标识调用用户，本身不具有认证或授权能力。</p>
 *
 * @param message 用户本次提交的问题，保留原始文本，不自动裁剪空格
 * @param userId 服务端确定的用户标识，用于向需要此参数的执行平台传递
 */
public record ChatCommand(String message, String userId) {

    /**
     * HTTP 入口已经使用 Bean Validation；这里继续保护非 HTTP 调用者，
     * 避免定时任务、测试或未来其他入口创建不完整的业务参数。
     */
    public ChatCommand {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
    }
}
