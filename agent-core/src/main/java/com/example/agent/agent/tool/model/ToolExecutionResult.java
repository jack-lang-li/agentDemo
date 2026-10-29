package com.example.agent.agent.tool.model;

import com.example.agent.agent.tool.enums.ToolErrorCode;
import com.example.agent.agent.tool.enums.ToolExecutionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * 工具执行结果的统一外层结构。
 *
 * status：执行成功或执行错误。
 * data：成功时的业务数据，例如订单查询结果。
 * error：失败时的错误信息。
 *
 * 不保存callId；调用关联仍由现有ToolResult负责。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ToolExecutionResult(
        ToolExecutionStatus status,
        Object data,
        ToolExecutionError error) {

    /**
     * 防止构造出相互矛盾的结果。
     * 成功时可以没有业务数据，但不能同时携带错误。
     * 失败时必须有错误信息，且不能携带业务数据。
     */
    public ToolExecutionResult {
        Objects.requireNonNull(status, "工具执行状态不能为空");

        if (status == ToolExecutionStatus.SUCCESS
                && error != null) {
            throw new IllegalArgumentException(
                    "成功结果不能携带错误信息"
            );
        }

        if (status == ToolExecutionStatus.ERROR
                && (error == null || data != null)) {
            throw new IllegalArgumentException(
                    "错误结果必须携带错误信息，且不能携带业务数据"
            );
        }
    }

    /**
     * 包装正常业务结果。
     * 查询完成但found=false，也属于执行成功。
     */
    public static ToolExecutionResult success(Object data) {
        return new ToolExecutionResult(
                ToolExecutionStatus.SUCCESS,
                data,
                null
        );
    }

    /**
     * 包装明确可反馈给模型的工具错误。
     * 此方法仅构造数据，不会执行重试。
     */
    public static ToolExecutionResult failure(
            ToolErrorCode code,
            String message) {

        return new ToolExecutionResult(
                ToolExecutionStatus.ERROR,
                null,
                new ToolExecutionError(code, message)
        );
    }
}