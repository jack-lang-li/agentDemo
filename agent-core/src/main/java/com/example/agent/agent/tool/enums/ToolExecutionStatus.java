package com.example.agent.agent.tool.enums;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 工具执行状态，不代表订单状态或整个Agent任务状态。
 */
public enum ToolExecutionStatus {

    SUCCESS,
    ERROR;

    /**
     * 明确约定写入JSON的值。
     * 例如SUCCESS序列化为字符串"SUCCESS"。
     */
    @JsonValue
    public String getValue() {
        return name();
    }
}