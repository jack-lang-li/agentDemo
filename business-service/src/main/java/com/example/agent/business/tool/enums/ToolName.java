package com.example.agent.business.tool.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 程序支持的工具名称。 */
@Getter
@RequiredArgsConstructor
public enum ToolName {
    QUERY_ORDER("query_order"),
    QUERY_LOGISTICS("query_logistics");

    private final String value;
}
