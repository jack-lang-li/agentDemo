package com.example.agent.controller;

import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 入口只负责接收请求、触发参数校验和返回结果；业务流程交给 Service。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        logger.info("聊天请求：message={}", request.getMessage());
        ChatResponse response = chatService.chat(request.getMessage());
        logger.info("聊天响应：answer={}", response.getAnswer());
        return response;
    }

    @PostMapping("/dify")
    public ChatResponse chatByDify(
            @Valid @RequestBody ChatRequest request) {

        return chatService.chatByDify(request.getMessage());
    }
}
