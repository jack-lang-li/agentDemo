package com.example.agent.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OutputItem {

    private String type;

    private String id;

    private String status;

    private List<ContentItem> content;

    private String name;

    private String arguments;

    @JsonProperty("call_id")
    private String callId;
}