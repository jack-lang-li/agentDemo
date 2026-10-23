package com.example.agent.client.model;

import lombok.Data;

import java.util.Map;

@Data
public class ModelTool {

    private String type;

    private String name;

    private String description;

    private Map<String, Object> parameters;
}