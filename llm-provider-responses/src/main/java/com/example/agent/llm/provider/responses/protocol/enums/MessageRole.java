package com.example.agent.llm.provider.responses.protocol.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 消息角色。 */
@Getter
@RequiredArgsConstructor
public enum MessageRole {
    SYSTEM("system"),
    USER("user");

    private final String value;
}
