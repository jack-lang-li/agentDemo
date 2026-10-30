package com.example.agent.business.chat.application.model;

import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;

/**
 * 一次聊天执行所需的业务参数。
 *
 * <p>message 来自业务请求；
 * caller 来自服务端身份入口。</p>
 *
 * <p>命令及调用者上下文均不可变，
 * 每次请求创建独立命令，适合显式传入 SSE 异步执行。</p>
 *
 * <p>当前聊天仅支持用户主体。
 * 应用主体的调用契约将在应用接入阶段定义。</p>
 *
 * @param message 本次用户问题，保留原始文本
 * @param caller 本次调用关联的服务端上下文
 */
public record ChatCommand(
        String message,
        CallerContext caller) {

    public ChatCommand {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "message 不能为空"
            );
        }

        if (caller == null) {
            throw new IllegalArgumentException(
                    "caller 不能为空"
            );
        }

        if (caller.callerType() != CallerType.USER) {
            throw new IllegalArgumentException(
                    "当前聊天仅支持 USER 类型调用者"
            );
        }
    }
}
