package com.example.agent.client.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 程序支持的工具名称。 */
@Getter
@RequiredArgsConstructor
public enum ToolName {
    QUERY_ORDER("query_order");

    private final String value;
}
