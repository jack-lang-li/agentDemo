package com.example.agent.tool;

import com.example.agent.client.enums.ToolName;
import com.example.agent.client.model.ModelTool;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface AgentTool {

    ToolName name();

    ModelTool definition();

    Map<String, Object> execute(JsonNode arguments);
}