package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 消息角色。 */
@Getter
@RequiredArgsConstructor
public enum MessageRole {
    USER("user");

    private final String value;
}
