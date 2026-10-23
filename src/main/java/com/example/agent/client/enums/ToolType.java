package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 工具定义的类型。 */
@Getter
@RequiredArgsConstructor
public enum ToolType {
    FUNCTION("function");

    private final String value;
}
