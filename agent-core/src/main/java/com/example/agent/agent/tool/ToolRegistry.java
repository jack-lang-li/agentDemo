package com.example.agent.agent.tool;

import com.example.agent.llm.api.model.ModelTool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 执行专用的工具集合，不是自动注入全部工具的 Spring 单例。
 *
 * <p>业务显式选择工具后再创建此对象；同一集合同时用于生成模型可见说明和查找实际实现。
 * 模型要求调用未提供的工具时立即拒绝，不回退到其他业务或全局容器查找。</p>
 */
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final List<ModelTool> definitions;

    public ToolRegistry(List<AgentTool> agentTools) {

        Map<String, AgentTool> registered = new LinkedHashMap<>();
        List<ModelTool> descriptions = new ArrayList<>();

        for (AgentTool tool : agentTools) {

            String name = tool.name();

            if (name == null || name.isBlank()) {
                throw new IllegalStateException("工具名称不能为空");
            }

            // 同名工具不能覆盖注册
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException(
                        "工具名称重复：" + name
                );
            }

            // 注册名称必须与发送给模型的名称一致
            ModelTool definition = tool.definition();

            if (definition == null
                    || !name.equals(definition.getName())) {
                throw new IllegalStateException(
                        "工具定义名称与注册名称不一致：" + name
                );
            }

            if (!"function".equals(definition.getType())
                    || definition.getDescription() == null
                    || definition.getDescription().isBlank()
                    || definition.getParameters() == null
                    || !"object".equals(definition.getParameters().get("type"))) {
                throw new IllegalStateException("工具必须提供 function 类型、用途描述和对象参数定义：" + name);
            }
            descriptions.add(definition);
        }

        this.tools = Collections.unmodifiableMap(registered);
        this.definitions = List.copyOf(descriptions);
    }

    public List<ModelTool> definitions() {
        return definitions;
    }

    public AgentTool getRequired(String name) {

        AgentTool tool = tools.get(name);

        if (tool == null) {
            throw new IllegalArgumentException(
                    "不支持的工具：" + name
            );
        }

        return tool;
    }
}
