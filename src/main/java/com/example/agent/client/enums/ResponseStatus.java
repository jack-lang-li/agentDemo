package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 当前用于判断模型响应是否完成的状态。 */
@Getter
@RequiredArgsConstructor
public enum ResponseStatus {
    COMPLETED("completed");

    private final String value;
}
