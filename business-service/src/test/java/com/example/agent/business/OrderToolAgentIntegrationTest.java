package com.example.agent.business;

import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.DefaultAgentRunner;
import com.example.agent.agent.AgentRequest;

import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.agent.tool.ToolCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.example.agent.testsupport.ModelFixtures.answer;
import static com.example.agent.testsupport.ModelFixtures.proposal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原 Agent 测试中的订单参数纠错场景，保留全部状态、调用次数及结果断言。 */
class OrderToolAgentIntegrationTest {

    private final ModelClient model = mock(ModelClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRunner runner = new DefaultAgentRunner(model, new ToolCallExecutor(mapper));

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

}
