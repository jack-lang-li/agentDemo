package com.example.agent.agent;

import com.example.agent.client.ModelClient;
import com.example.agent.client.model.ModelInput;
import com.example.agent.support.RecordingTool;
import com.example.agent.tool.OrderTool;
import com.example.agent.tool.ToolCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.example.agent.support.ModelFixtures.answer;
import static com.example.agent.support.ModelFixtures.proposal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证通用 Agent 与业务工具解耦，包含同步、流式和并发请求之间的工具及历史隔离。 */
class DefaultAgentRunnerTest {

    private final ModelClient model = mock(ModelClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRunner runner = new DefaultAgentRunner(model, new ToolCallExecutor(mapper));

    @Test
    void shouldAllowAgentRequestWithoutAnyTools() {
        when(model.generate(any())).thenReturn(answer("普通回答"));

        assertThat(runner.run(new AgentRequest("问题", List.of()))).isEqualTo("普通回答");
        ArgumentCaptor<ModelInput> input = ArgumentCaptor.forClass(ModelInput.class);
        verify(model).generate(input.capture());
        assertThat(input.getValue().tools()).isEmpty();
        assertThat(input.getValue().turns()).isEmpty();
    }

    @Test
    void shouldRejectAnotherBusinessToolEvenAfterThatToolWasUsedByPreviousRequest() {
        RecordingTool orders = new RecordingTool("business_a_query", Map.of("answer", "A"));
        RecordingTool inventory = new RecordingTool("business_b_query", Map.of("answer", "B"));
        when(model.generate(any()))
                .thenReturn(proposal("call-a", orders.name(), "{}"), answer("A 的回答"),
                        proposal("call-b", orders.name(), "{}"));

        assertThat(runner.run(new AgentRequest("A 的提示词", "A 的问题", List.of(orders)))).isEqualTo("A 的回答");
        assertThatThrownBy(() -> runner.run(new AgentRequest("B 的提示词", "B 的问题", List.of(inventory))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持的工具");
        assertThat(orders.executions()).hasValue(1);
        assertThat(inventory.executions()).hasValue(0);

        ArgumentCaptor<ModelInput> inputs = ArgumentCaptor.forClass(ModelInput.class);
        verify(model, times(3)).generate(inputs.capture());
        assertThat(inputs.getAllValues().get(0).tools()).extracting(tool -> tool.getName())
                .containsExactly("business_a_query");
        assertThat(inputs.getAllValues().get(1).turns()).hasSize(1);
        ModelInput secondBusiness = inputs.getAllValues().get(2);
        assertThat(secondBusiness.systemPrompt()).isEqualTo("B 的提示词");
        assertThat(secondBusiness.turns()).isEmpty();
        assertThat(secondBusiness.tools()).extracting(tool -> tool.getName()).containsExactly("business_b_query");
    }

    @Test
    void shouldUseSameLoopForStreamingToolCallsAndPreserveStatusOrder() throws Exception {
        RecordingTool tool = new RecordingTool("business_query", Map.of("ok", true));
        AtomicInteger round = new AtomicInteger();
        when(model.generateStream(any(), any())).thenAnswer(invocation -> {
            if (round.getAndIncrement() == 0) {
                return proposal("call-1", tool.name(), "{}");
            }
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("查到");
            delta.accept("结果");
            return answer("查到结果");
        });
        List<String> deltas = new ArrayList<>();
        List<String> statuses = new ArrayList<>();

        runner.runStream(new AgentRequest("问题", List.of(tool)), deltas::add, statuses::add);

        assertThat(deltas).containsExactly("查到", "结果");
        assertThat(statuses).containsExactly("正在请求模型，第 1 轮", "正在执行工具",
                "工具执行完成，正在继续生成回答", "正在请求模型，第 2 轮");
        assertThat(tool.executions()).hasValue(1);
        ArgumentCaptor<ModelInput> inputs = ArgumentCaptor.forClass(ModelInput.class);
        verify(model, times(2)).generateStream(inputs.capture(), any());
        var payload = mapper.readTree(inputs.getAllValues().get(1).turns().get(0).toolResults().get(0).output());
        assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(payload.path("data").path("ok").asBoolean()).isTrue();
        assertThat(payload.path("error").isNull()).isTrue();
    }

    @Test
    void shouldContinueStreamingAfterArgumentErrorAndPreserveBothCallIds() throws Exception {
        OrderTool tool = spy(new OrderTool());
        AtomicInteger round = new AtomicInteger();
        when(model.generateStream(any(), any())).thenAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            int current = round.incrementAndGet();
            if (current == 1) {
                return proposal("bad-call", tool.name(), "{\"orderId\":10001}");
            }

            var firstResult = input.turns().get(0).toolResults().get(0);
            assertThat(firstResult.callId()).isEqualTo("bad-call");
            var failure = mapper.readTree(firstResult.output());
            assertThat(failure.path("status").asText()).isEqualTo("ERROR");
            assertThat(failure.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
            if (current == 2) {
                verify(tool, never()).queryOrder(anyString());
                return proposal("corrected-call", tool.name(), "{\"orderId\":\"10001\"}");
            }

            assertThat(input.turns()).hasSize(2);
            var secondResult = input.turns().get(1).toolResults().get(0);
            assertThat(secondResult.callId()).isEqualTo("corrected-call");
            var success = mapper.readTree(secondResult.output());
            assertThat(success.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(success.path("data").path("orderId").asText()).isEqualTo("10001");
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("订单10001已发货");
            return answer("订单10001已发货");
        });
        List<String> deltas = new ArrayList<>();
        List<String> statuses = new ArrayList<>();

        runner.runStream(new AgentRequest("查询订单10001", List.of(tool)), deltas::add, statuses::add);

        assertThat(deltas).containsExactly("订单10001已发货");
        assertThat(statuses).containsExactly("正在请求模型，第 1 轮", "正在执行工具",
                "工具执行完成，正在继续生成回答", "正在请求模型，第 2 轮", "正在执行工具",
                "工具执行完成，正在继续生成回答", "正在请求模型，第 3 轮");
        verify(model, times(3)).generateStream(any(), any());
        verify(tool, times(1)).queryOrder("10001");
        verify(tool, times(1)).queryOrder(anyString());
    }

    @Test
    void shouldRespectPerBusinessRoundLimitWithoutExecutingLastRoundTools() {
        RecordingTool tool = new RecordingTool("business_query", "结果");
        when(model.generateStream(any(), any())).thenReturn(
                proposal("call-1", tool.name(), "{}"), proposal("call-2", tool.name(), "{}"));

        assertThatThrownBy(() -> runner.runStream(new AgentRequest(null, "问题", List.of(tool), 2),
                text -> { }, status -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("轮数上限")
                .hasMessageContaining("2");
        assertThat(tool.executions()).hasValue(1);
        verify(model, times(2)).generateStream(any(), any());
    }

    @Test
    void shouldPropagateStreamCallbackFailureBeforeAnyToolExecution() {
        RecordingTool tool = new RecordingTool("business_query", "结果");
        when(model.generateStream(any(), any())).thenAnswer(invocation -> {
            Consumer<String> delta = invocation.getArgument(1);
            delta.accept("生成中的正文");
            return proposal("call-1", tool.name(), "{}");
        });
        UncheckedIOException failure = new UncheckedIOException(new IOException("模拟浏览器连接断开"));

        assertThatThrownBy(() -> runner.runStream(new AgentRequest("问题", List.of(tool)),
                text -> { throw failure; }, status -> { })).isSameAs(failure);
        assertThat(tool.executions()).hasValue(0);
        verify(model).generateStream(any(), any());
    }

    @Test
    void shouldKeepConcurrentBusinessToolsAndHistoriesIndependent() throws Exception {
        RecordingTool first = new RecordingTool("first_business", "A");
        RecordingTool second = new RecordingTool("second_business", "B");
        CyclicBarrier overlap = new CyclicBarrier(2);
        when(model.generate(any())).thenAnswer(invocation -> {
            ModelInput input = invocation.getArgument(0);
            String name = input.message().equals("A") ? first.name() : second.name();
            assertThat(input.tools()).extracting(tool -> tool.getName()).containsExactly(name);
            if (input.turns().isEmpty()) {
                overlap.await(3, TimeUnit.SECONDS);
                return proposal("call-" + input.message(), name, "{}");
            }
            assertThat(input.turns()).hasSize(1);
            var payload = mapper.readTree(input.turns().get(0).toolResults().get(0).output());
            assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(payload.path("data").asText()).isEqualTo(input.message());
            return answer(input.message() + " 的回答");
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstAnswer = workers.submit(() -> runner.run(new AgentRequest("A", List.of(first))));
            var secondAnswer = workers.submit(() -> runner.run(new AgentRequest("B", List.of(second))));
            assertThat(firstAnswer.get(5, TimeUnit.SECONDS)).isEqualTo("A 的回答");
            assertThat(secondAnswer.get(5, TimeUnit.SECONDS)).isEqualTo("B 的回答");
        } finally {
            workers.shutdownNow();
        }
        assertThat(first.executions()).hasValue(1);
        assertThat(second.executions()).hasValue(1);
    }
}
