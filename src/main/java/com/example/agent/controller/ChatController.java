package com.example.agent.controller;

import com.example.agent.common.SseWriter;
import com.example.agent.dto.ChatRequest;
import com.example.agent.dto.ChatResponse;
import com.example.agent.executor.ChatExecutorCodes;
import com.example.agent.service.ChatService;
import com.example.agent.service.model.ChatCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 入口只负责接收请求、触发参数校验和返回结果；业务流程交给 Service。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    // 目前没有登录系统，沿用原有测试用户。后续从认证上下文获取，不能信任前端自报身份。
    private static final String DEMO_USER_ID = "demo-user-1";

    private final ChatService chatService;

    private final ObjectMapper objectMapper;

    @PostMapping(
            value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> chat(
            @Valid @RequestBody ChatRequest request) {

        // 当前由服务端入口固定选择执行方式，前端请求体仍然只需要 message。
        ChatCommand command = new ChatCommand(request.getMessage(), DEMO_USER_ID);

        StreamingResponseBody body = outputStream -> {

            SseWriter writer =
                    new SseWriter(outputStream, objectMapper);

            try {
                writer.send(
                        "status",
                        Map.of("message", "请求已接收")
                );

                chatService.chatStream(
                        ChatExecutorCodes.LOCAL_AGENT,
                        command,

                        text -> writer.send(
                                "delta",
                                Map.of("text", text)
                        ),

                        message -> writer.send(
                                "status",
                                Map.of("message", message)
                        )
                );

                // 整个 Agent 循环成功结束后，才发 done。
                writer.send("done", Map.of());

            } catch (UncheckedIOException exception) {
                logger.debug("流式连接已断开或写入失败");
                throw exception.getCause();

            } catch (Exception exception) {
                logger.error("流式 Agent 执行失败", exception);

                try {
                    writer.send(
                            "error",
                            Map.of(
                                    "message",
                                    "本次生成失败，请稍后重试"
                            )
                    );

                } catch (UncheckedIOException writeException) {
                    throw writeException.getCause();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(new MediaType(
                        "text",
                        "event-stream",
                        StandardCharsets.UTF_8
                ))
                .header(
                        HttpHeaders.CACHE_CONTROL,
                        "no-cache, no-transform"
                )
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    @PostMapping("/dify")
    public ChatResponse chatByDify(
            @Valid @RequestBody ChatRequest request) {

        return chatService.chat(
                ChatExecutorCodes.DIFY_WORKFLOW,
                new ChatCommand(request.getMessage(), DEMO_USER_ID)
        );
    }
}
