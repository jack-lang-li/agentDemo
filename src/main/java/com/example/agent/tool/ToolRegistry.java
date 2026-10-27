package com.example.agent.tool;

import com.example.agent.client.model.ModelTool;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> agentTools) {

        Map<String, AgentTool> registered = new LinkedHashMap<>();

        for (AgentTool tool : agentTools) {

            String name = tool.name().getValue();

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
        }

        this.tools = Collections.unmodifiableMap(registered);
    }

    public List<ModelTool> definitions() {
        return tools.values()
                .stream()
                .map(AgentTool::definition)
                .toList();
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