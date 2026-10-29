package com.example.agent.agent.tool.exception;

/**
 * 工具输入参数不符合要求。
 *
 * 仅用于实际业务操作之前的参数校验。
 * message会反馈给模型，应明确说明哪个参数需要修正。
 *
 * 程序内部错误不要转换成此异常。
 */
public final class InvalidToolArgumentsException
        extends IllegalArgumentException {

    public InvalidToolArgumentsException(String message) {
        super(message);

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "工具参数错误说明不能为空"
            );
        }
    }
}