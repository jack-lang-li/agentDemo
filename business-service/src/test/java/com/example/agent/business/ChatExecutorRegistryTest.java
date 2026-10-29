package com.example.agent.business;

import com.example.agent.business.chat.execution.ChatExecutor;
import com.example.agent.business.chat.execution.ChatExecutorRegistry;
import com.example.agent.business.chat.execution.StreamingChatExecutor;
import com.example.agent.business.chat.application.model.ChatCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证策略注册冲突和流式能力边界，无需启动 Spring 或访问外部服务。 */
class ChatExecutorRegistryTest {

    @Test
    void shouldRejectDuplicateCodesInsteadOfSilentlyReplacingAnExecutor() {
        assertThatThrownBy(() -> new ChatExecutorRegistry(List.of(
                new BlockingExecutor("duplicate"), new BlockingExecutor("duplicate"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("编码重复")
                .hasMessageContaining("duplicate");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRejectInvalidCodesAtStartup(String code) {
        assertThatThrownBy(() -> new ChatExecutorRegistry(List.of(new BlockingExecutor(code))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("编码不能为空");
    }

    @Test
    void shouldRejectAnEmptyRegistryAtStartup() {
        assertThatThrownBy(() -> new ChatExecutorRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少需要注册一个");
    }

    @Test
    void shouldFailExplicitlyForUnknownCodes() {
        ChatExecutorRegistry registry = new ChatExecutorRegistry(List.of(new BlockingExecutor("known")));

        assertThatThrownBy(() -> registry.getRequired("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的聊天执行方式")
                .hasMessageContaining("unknown");
    }

    @Test
    void shouldDistinguishStreamCapableExecutorsFromBlockingExecutors() {
        ChatExecutor blocking = new BlockingExecutor("blocking");
        StreamingChatExecutor streaming = new StreamingExecutor();
        ChatExecutorRegistry registry = new ChatExecutorRegistry(List.of(blocking, streaming));

        assertThat(registry.getRequired("blocking")).isSameAs(blocking);
        assertThat(registry.getStreamingRequired("streaming")).isSameAs(streaming);
        assertThatThrownBy(() -> registry.getStreamingRequired("blocking"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持流式输出")
                .hasMessageContaining("blocking");
    }

    private record BlockingExecutor(String code) implements ChatExecutor {
        @Override
        public String execute(ChatCommand command) {
            return "完整回答";
        }
    }

    private static class StreamingExecutor implements StreamingChatExecutor {
        @Override
        public String code() {
            return "streaming";
        }

        @Override
        public String execute(ChatCommand command) {
            return "完整回答";
        }

        @Override
        public void executeStream(ChatCommand command, Consumer<String> onDelta, Consumer<String> onStatus) {
            onDelta.accept("正文增量");
        }
    }
}
