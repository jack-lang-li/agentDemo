package com.example.agent.tool;

import com.example.agent.client.model.ModelTool;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 由业务实现并按请求提供给 Agent 的工具契约。
 *
 * <p>definition 说明用途与参数，execute 执行实际业务操作；模型只提出调用意图。
 * 工具名使用字符串，不要求修改公共枚举。工具可返回 Map、列表、DTO 或其他可序列化对象，
 * 公共执行组件统一转换成 JSON。参数及业务权限校验由工具自己的业务实现负责。</p>
 */
public interface AgentTool {

    /** 本次工具集合内唯一的名称，应与 definition 中的名称一致。 */
    String name();

    /** 包含 function 类型、名称、用途描述及参数 JSON Schema 的工具说明。 */
    ModelTool definition();

    /** 执行业务逻辑；arguments 已完成 JSON 对象校验，字段与业务规则仍需由实现检查。 */
    Object execute(JsonNode arguments);
}
