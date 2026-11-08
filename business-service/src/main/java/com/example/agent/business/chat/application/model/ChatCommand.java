package com.example.agent.business.chat.application.model;

import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;

/**
 * 一次聊天执行所需的业务参数。
 *
 * <p>message 来自业务请求；
 *  * authorization 来自可信服务端入口，包含身份与权限快照。</p>
 *
 * <p>命令及调用者上下文均不可变，
 * 每次请求创建独立命令，适合显式传入 SSE 异步执行。</p>
 *
 * <p>当前聊天仅支持用户主体。
 * 应用主体的调用契约将在应用接入阶段定义。</p>
 *
 * @param message 本次用户问题，保留原始文本
 * @param authorization 本次调用关联的授权信息
 */
public record ChatCommand(
        String message,
        CallerAuthorization authorization) {

    public ChatCommand {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "message 不能为空"
            );
        }

        if (authorization == null) {
            throw new IllegalArgumentException(
                    "authorization 不能为空"
            );
        }

        if (authorization.caller().callerType() != CallerType.USER) {
            throw new IllegalArgumentException(
                    "当前聊天仅支持 USER 类型调用者"
            );
        }
    }
    /**
     * 身份由同一份授权快照提供，
     * 避免分别保存两份身份而出现不一致。
     */
    public CallerContext caller() {
        return authorization.caller();
    }
}
