package com.example.agent.business;

import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 保护非 HTTP 调用者也必须遵守的业务命令边界。 */
class ChatCommandTest {

    private final CallerContext caller = new CallerContext("tenant-1", CallerType.USER, "user-1");
    private final CallerAuthorization authorization = new CallerAuthorization(caller, Set.of());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void shouldRejectBlankMessage(String message) {
        assertThatThrownBy(() -> new ChatCommand(message, authorization))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("message 不能为空");
    }

    @Test
    void shouldRejectCommandWithoutCaller() {
        assertThatThrownBy(() -> new ChatCommand("你好", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("authorization 不能为空");
    }

    @Test
    void shouldRejectApplicationForCurrentUserOnlyChatContract() {
        CallerContext application = new CallerContext("tenant-1", CallerType.APPLICATION, "app-1");
        assertThatThrownBy(() -> new ChatCommand("你好", new CallerAuthorization(application, Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("当前聊天仅支持 USER 类型调用者");
    }

    @Test
    void shouldKeepOriginalMessageAndExplicitCaller() {
        ChatCommand command = new ChatCommand("  原始输入\n", authorization);
        assertThat(command.message()).isEqualTo("  原始输入\n");
        assertThat(command.caller()).isSameAs(caller);
        assertThat(command.authorization()).isSameAs(authorization);
    }
}
