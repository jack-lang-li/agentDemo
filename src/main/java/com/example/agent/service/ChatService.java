package com.example.agent.service;

import com.example.agent.client.DifyWorkflowClient;
import com.example.agent.client.ModelClient;
import com.example.agent.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 编排 AI 业务流程，目前仅调用模型并组装响应。
 * 后续学习 Agent 时，业务编排从这一层逐步演进。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ModelClient modelClient;
    private final DifyWorkflowClient difyWorkflowClient;


    public ChatResponse chat(String message) {
        String answer = modelClient.chat(message);
        return new ChatResponse(answer);
    }

    public ChatResponse chatByDify(String message) {
        String answer = difyWorkflowClient.run(
                message,
                "demo-user-1"
        );

        return new ChatResponse(answer);
    }
}
