package com.example.agent.client.model;

import lombok.Data;

import java.util.Map;

/**
 * 模型可见的工具说明，由具体业务工具提供；此对象不包含 Java 执行实例。
 * 一个请求中的说明必须与实际开放的工具名称对应，业务应在 definition() 中稳定构建。
 */
@Data
public class ModelTool {

    /** 当前函数工具使用 function，由协议适配器发送给模型平台。 */
    private String type;

    /** 本次任务内唯一的名称，与 AgentTool.name() 一致。 */
    private String name;

    /** 清楚说明用途、适用场景和限制，帮助模型决定何时使用该工具。 */
    private String description;

    /** 参数对象的 JSON Schema；工具执行时仍需自行校验具体字段和业务规则。 */
    private Map<String, Object> parameters;
}
