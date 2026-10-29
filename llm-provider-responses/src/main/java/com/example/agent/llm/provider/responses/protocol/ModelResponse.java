package com.example.agent.llm.provider.responses.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelResponse {

    private String id;

    private String status;

    private String model;

    private List<OutputItem> output;
}