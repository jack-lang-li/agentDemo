package com.example.agent.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单独隔离模型交互，避免 Controller 和 Service 依赖具体模型的通信细节。
 * 先用 Mock 验证链路，无需 API Key、网络或费用。
 * 阶段 2 在这里学习 HTTP 调用；阶段 3 再抽象 ModelProvider，支持多模型。
 */
@Component
@ConditionalOnProperty(prefix = "agent.model", name = "provider", havingValue = "mock",
        matchIfMissing = true)
public class ModelClient {

    public String generate(String message) {
        return "模型收到你的问题：" + message;
    }
}
