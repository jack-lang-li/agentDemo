package com.example.agent.business.chat.web;

import com.example.agent.business.identity.api.CurrentCallerProvider;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.web.sse.SseWriter;
import com.example.agent.business.chat.dto.ChatRequest;
import com.example.agent.business.chat.dto.ChatResponse;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.application.ChatService;
import com.example.agent.business.chat.application.model.ChatCommand;
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


    private final ChatService chatService;

    private final ObjectMapper objectMapper;

    private final CurrentCallerProvider currentCallerProvider;

    @PostMapping(
            value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<StreamingResponseBody> chat(
            @Valid @RequestBody ChatRequest request) {

        // 在入口创建命令，身份获取发生在异步回调之前。
        ChatCommand command = createCommand(request);

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

        ChatCommand command = createCommand(request);

        return chatService.chat(
                ChatExecutorCodes.DIFY_WORKFLOW,
                command
        );
    }

    /**
     * 统一创建聊天业务命令。
     *
     * 请求体只提供业务参数；
     * 调用者上下文由服务端身份入口提供。
     */
    private ChatCommand createCommand(ChatRequest request) {
        CallerContext caller =
                currentCallerProvider.requireCurrentCaller();

        return new ChatCommand(
                request.getMessage(),
                caller
        );
    }
}
