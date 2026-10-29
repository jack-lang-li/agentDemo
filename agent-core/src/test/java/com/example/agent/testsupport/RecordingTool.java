package com.example.agent.testsupport;

import com.example.agent.llm.api.model.ModelTool;
import com.example.agent.agent.tool.AgentTool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 任意业务自定义名称与返回类型的测试工具，不依赖内置 ToolName 枚举。 */
public record RecordingTool(String name, Object result, AtomicInteger executions) implements AgentTool {

    public RecordingTool(String name, Object result) {
        this(name, result, new AtomicInteger());
    }

    @Override
    public ModelTool definition() {
        ModelTool tool = new ModelTool();
        tool.setName(name);
        tool.setType("function");
        tool.setDescription("当前测试业务提供的 " + name + " 能力");
        tool.setParameters(Map.of("type", "object", "properties", Map.of()));
        return tool;
    }

    @Override
    public Object execute(JsonNode arguments) {
        executions.incrementAndGet();
        return result;
    }
}
