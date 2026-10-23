package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 工具选择策略。 */
@Getter
@RequiredArgsConstructor
public enum ToolChoice {
    AUTO("auto");

    private final String value;
}
