package com.example.agent.business;

import com.example.agent.business.chat.application.ChatService;
import com.example.agent.business.chat.application.model.ChatCommand;
import com.example.agent.business.chat.dto.ChatRequest;
import com.example.agent.business.chat.execution.ChatExecutorCodes;
import com.example.agent.business.chat.web.ChatController;
import com.example.agent.business.identity.api.CurrentCallerProvider;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
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
import static org.mockito.Mockito.when;

/** 身份在入口取快照，工作线程不查询 ThreadLocal，也不串用其他请求身份。 */
class ChatControllerCallerContextTest {

    @Test
    void shouldKeepEachCallerWhenStreamsRunOnWorkerThreads() throws Exception {
        ChatService service = mock(ChatService.class);
        CurrentCallerProvider provider = mock(CurrentCallerProvider.class);
        CallerContext callerA = new CallerContext("tenant-a", CallerType.USER, "user-a");
        CallerContext callerB = new CallerContext("tenant-b", CallerType.USER, "user-b");
        when(provider.requireCurrentCaller()).thenReturn(callerA, callerB);
        ChatController controller = new ChatController(service, new ObjectMapper(), provider);
        ChatRequest firstRequest = new ChatRequest();
        firstRequest.setMessage("问题 A");
        ChatRequest secondRequest = new ChatRequest();
        secondRequest.setMessage("问题 B");
        StreamingResponseBody firstBody = Objects.requireNonNull(controller.chat(firstRequest).getBody());
        StreamingResponseBody secondBody = Objects.requireNonNull(controller.chat(secondRequest).getBody());
        verify(provider, times(2)).requireCurrentCaller();

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
                new ChatCommand("问题 A", callerA), new ChatCommand("问题 B", callerB));
        verify(provider, times(2)).requireCurrentCaller();
    }
}
