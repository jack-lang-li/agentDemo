package com.example.agent.client;

import com.example.agent.client.model.ModelMessage;
import com.example.agent.client.model.ModelRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 单独隔离模型交互，避免 Controller 和 Service 依赖具体模型的通信细节。
 * 先用 Mock 验证链路，无需 API Key、网络或费用。
 * 阶段 2 在这里学习 HTTP 调用；阶段 3 再抽象 ModelProvider，支持多模型。
 */
@Component
@ConditionalOnProperty(prefix = "agent.model", name = "provider", havingValue = "mock",
        matchIfMissing = true)
public class ModelClient {

    public String chat(String message) {

        List<ModelMessage> messages = new ArrayList<>();

        messages.add(
                new ModelMessage(
                        "system",
                        "你是一个Java智能体学习助手，请使用中文回答。"
                )
        );

        messages.add(
                new ModelMessage(
                        "user",
                        message
                )
        );

        ModelRequest request = new ModelRequest();
        request.setModel("mock-model");
        request.setMessages(messages);

        System.out.println(request);

        return "当前已经完成模型请求对象组装：" + message;
    }
}
