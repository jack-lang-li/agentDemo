package com.example.agent.business;

import com.example.agent.business.chat.application.ChatService;
import com.example.agent.business.chat.execution.ChatExecutor;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.execution.ChatExecutorRegistry;
import com.example.agent.business.chat.execution.StreamingChatExecutor;
import com.example.agent.business.chat.application.DefaultChatService;
import com.example.agent.business.chat.application.model.ChatCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证业务入口可扩展性、回调顺序以及异常传播，不依赖具体平台客户端。 */
class DefaultChatServiceTest {

    private final ChatCommand command = new ChatCommand("你好", "user-42");
    private StreamingChatExecutor local;
    private ChatExecutor dify;
    private ChatService service;

    @BeforeEach
    void setUp() {
        local = mock(StreamingChatExecutor.class);
        dify = mock(ChatExecutor.class);
        when(local.code()).thenReturn(ChatExecutorCodes.LOCAL_AGENT);
        when(dify.code()).thenReturn(ChatExecutorCodes.DIFY_WORKFLOW);
        service = new DefaultChatService(new ChatExecutorRegistry(List.of(local, dify)));
    }

    @Test
    void shouldSupportAnAdditionalStrategyWithoutAddingServiceBranches() {
        // 使用第三种业务编码验证开放扩展，服务实现和注册表都没有此编码的专用分支。
        ChatExecutor additional = new ChatExecutor() {
            @Override
            public String code() {
                return "another-platform";
            }

            @Override
            public String execute(ChatCommand input) {
                return input.userId() + " 收到：" + input.message();
            }
        };
        ChatService extended = new DefaultChatService(new ChatExecutorRegistry(List.of(local, dify, additional)));

        assertThat(extended.chat("another-platform", command).getAnswer()).isEqualTo("user-42 收到：你好");
        verify(local, never()).execute(any());
        verify(dify, never()).execute(any());
    }

    @Test
    void shouldForwardDeltaAndStatusCallbacksInOrder() {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            Consumer<String> onStatus = invocation.getArgument(2);
            onStatus.accept("正在执行工具");
            onDelta.accept("第一段");
            onDelta.accept("第二段");
            return null;
        }).when(local).executeStream(eq(command), any(), any());
        List<String> events = new ArrayList<>();

        service.chatStream(ChatExecutorCodes.LOCAL_AGENT, command,
                text -> events.add("delta:" + text), status -> events.add("status:" + status));

        assertThat(events).containsExactly("status:正在执行工具", "delta:第一段", "delta:第二段");
        verify(dify, never()).execute(any());
    }

    @Test
    void shouldPropagateCallbackFailureAndStopBeforeFurtherOutput() {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("第一段");
            onDelta.accept("不应再执行到这里");
            return null;
        }).when(local).executeStream(eq(command), any(), any());
        UncheckedIOException writeFailure = new UncheckedIOException(new IOException("模拟连接断开"));
        List<String> received = new ArrayList<>();

        assertThatThrownBy(() -> service.chatStream(ChatExecutorCodes.LOCAL_AGENT, command, text -> {
            received.add(text);
            throw writeFailure;
        }, status -> { }))
                .isSameAs(writeFailure);
        assertThat(received).containsExactly("第一段");
    }

    @Test
    void shouldRejectBlockingOnlyStrategyWithoutExecutingOrFallingBack() {
        assertThatThrownBy(() -> service.chatStream(ChatExecutorCodes.DIFY_WORKFLOW,
                command, text -> { }, status -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持流式输出");

        verify(dify, never()).execute(any());
        verify(local, never()).executeStream(any(), any(), any());
    }
}
