package com.example.agent.agent.tool.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 可以反馈给模型的工具错误类型。
 * 当前只定义参数错误，其他类型按后续需求增加。
 */
public enum ToolErrorCode {

    INVALID_ARGUMENTS;

    @JsonValue
    public String getValue() {
        return name();
    }
}