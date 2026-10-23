package com.example.agent.client.model;

import lombok.Data;

import java.util.List;

@Data
public class ModelRequest {

    /**
     * 使用哪个大模型
     */
    private String model;

    /**
     * 当前发送给模型的上下文消息
     */
    private List<ModelMessage> input;
}