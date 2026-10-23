package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 模型输出项及工具执行结果的类型。 */
@Getter
@RequiredArgsConstructor
public enum OutputItemType {
    MESSAGE("message"),
    FUNCTION_CALL("function_call"),
    FUNCTION_CALL_OUTPUT("function_call_output");

    private final String value;
}
