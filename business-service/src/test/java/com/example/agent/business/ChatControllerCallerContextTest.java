package com.example.agent.business;

import com.example.agent.business.chat.application.ChatService;
import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.chat.dto.ChatRequest;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.web.ChatController;
import com.example.agent.business.identity.api.CurrentCallerProvider;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 身份及权限在入口取快照，工作线程不重新查询入口或串用其他请求的授权。 */
class ChatControllerCallerContextTest {

    @Test
    void shouldKeepEachCallerWhenStreamsRunOnWorkerThreads() throws Exception {
        ChatService service = mock(ChatService.class);
        CurrentCallerProvider provider = mock(CurrentCallerProvider.class);
        CallerContext callerA = new CallerContext("tenant-a", CallerType.USER, "user-a");
        CallerContext callerB = new CallerContext("tenant-b", CallerType.USER, "user-b");
        CallerAuthorization authorizationA = new CallerAuthorization(callerA,
                Set.of("chat:agent:invoke", "order:query"));
        CallerAuthorization authorizationB = new CallerAuthorization(callerB,
                Set.of("chat:agent:invoke", "logistics:query"));
        when(provider.requireCurrentAuthorization()).thenReturn(authorizationA, authorizationB);
        ChatController controller = new ChatController(service, new ObjectMapper(), provider);
        ChatRequest firstRequest = new ChatRequest();
        firstRequest.setMessage("问题 A");
        ChatRequest secondRequest = new ChatRequest();
        secondRequest.setMessage("问题 B");
        StreamingResponseBody firstBody = Objects.requireNonNull(controller.chat(firstRequest).getBody());
        StreamingResponseBody secondBody = Objects.requireNonNull(controller.chat(secondRequest).getBody());
        verify(provider, times(2)).requireCurrentAuthorization();

        // 后续再查询入口会失败，异步执行必须使用已经创建的快照。
        when(provider.requireCurrentAuthorization()).thenThrow(new IllegalStateException("入口请求已结束"));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> {
                firstBody.writeTo(new ByteArrayOutputStream());
                return null;
            });
            Future<?> second = workers.submit(() -> {
                secondBody.writeTo(new ByteArrayOutputStream());
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        ArgumentCaptor<ChatCommand> commands = ArgumentCaptor.forClass(ChatCommand.class);
        verify(service, times(2)).chatStream(eq(ChatExecutorCodes.LOCAL_AGENT), commands.capture(), any(), any());
        assertThat(commands.getAllValues()).containsExactlyInAnyOrder(
                new ChatCommand("问题 A", authorizationA), new ChatCommand("问题 B", authorizationB));
        verify(provider, times(2)).requireCurrentAuthorization();
        verifyNoMoreInteractions(provider);
    }
}
