package com.example.agent.business;

import com.example.agent.agent.AgentRequest;
import com.example.agent.agent.AgentRunner;
import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.chat.execution.impl.LocalAgentExecutor;
import com.example.agent.business.logistics.tool.LogisticsTool;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.business.security.authorization.AuthorizedAgentTool;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 阻塞入口与流式 HTTP 验证互补，保护业务适配器按请求选择工具的行为。 */
class LocalAgentExecutorAuthorizationTest {

    @ParameterizedTest
    @MethodSource("toolGrants")
    void shouldSelectOnlyGrantedToolsForBlockingExecution(Set<String> permissions, List<String> expectedTools) {
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.run(any())).thenReturn("回答");
        LocalAgentExecutor executor = new LocalAgentExecutor(runner, new OrderTool(), new LogisticsTool());
        CallerAuthorization authorization = new CallerAuthorization(
                new CallerContext("tenant-1", CallerType.USER, "user-1"), permissions);

        assertThat(executor.execute(new ChatCommand("你好", authorization))).isEqualTo("回答");

        var request = ArgumentCaptor.forClass(AgentRequest.class);
        verify(runner).run(request.capture());
        assertThat(request.getValue().message()).isEqualTo("你好");
        assertThat(request.getValue().tools()).extracting(tool -> tool.name())
                .containsExactlyElementsOf(expectedTools);
        assertThat(request.getValue().tools()).allMatch(AuthorizedAgentTool.class::isInstance);
    }

    static Stream<Arguments> toolGrants() {
        return Stream.of(
                Arguments.of(Set.of("chat:agent:invoke"), List.of()),
                Arguments.of(Set.of("chat:agent:invoke", "order:query"), List.of("query_order")),
                Arguments.of(Set.of("chat:agent:invoke", "logistics:query"), List.of("query_logistics")),
                Arguments.of(Set.of("chat:agent:invoke", "order:query", "logistics:query"),
                        List.of("query_order", "query_logistics"))
        );
    }
}
