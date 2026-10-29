package com.example.agent.client.model;



import com.example.agent.client.enums.ToolErrorCode;

import java.util.Objects;

/**
 * 工具执行错误的反馈信息。
 *
 * @param code 稳定的错误分类
 * @param message 面向模型的具体错误说明，帮助判断如何处理
 */
public record ToolExecutionError(
        ToolErrorCode code,
        String message) {

    public ToolExecutionError {
        Objects.requireNonNull(code, "工具错误码不能为空");

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "工具错误说明不能为空"
            );
        }
    }
}