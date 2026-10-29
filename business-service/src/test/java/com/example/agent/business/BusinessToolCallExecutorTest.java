package com.example.agent.business;

import com.example.agent.agent.tool.ToolCallExecutor;
import com.example.agent.agent.tool.ToolRegistry;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.business.logistics.tool.LogisticsTool;

import com.example.agent.llm.api.model.ToolCall;
import com.example.agent.testsupport.RecordingTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** 原工具执行测试中的订单、物流场景，避免通用核心反向依赖业务工具。 */
class BusinessToolCallExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallExecutor executor = new ToolCallExecutor(mapper);

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderId\":null}", "{\"orderId\":10001}",
            "{\"orderId\":\"\"}", "{\"orderId\":\"   \"}"})
    void shouldReturnOrderArgumentErrorWithoutQuerying(String arguments) throws Exception {
        OrderTool tool = spy(new OrderTool());
        var results = executor.execute(List.of(new ToolCall("call-1", tool.name(), arguments)),
                new ToolRegistry(List.of(tool)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
        assertArgumentError(results.get(0).output(), "orderId必须是非空字符串");
        verify(tool, never()).queryOrder(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"trackingNo\":null}", "{\"trackingNo\":10001}",
            "{\"trackingNo\":\"\"}", "{\"trackingNo\":\"   \"}"})
    void shouldReturnLogisticsArgumentErrorWithoutQuerying(String arguments) throws Exception {
        LogisticsTool tool = spy(new LogisticsTool());
        var results = executor.execute(List.of(new ToolCall("call-1", tool.name(), arguments)),
                new ToolRegistry(List.of(tool)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
        assertArgumentError(results.get(0).output(), "trackingNo必须是非空字符串");
        verify(tool, never()).queryLogistics(anyString());
    }

    @Test
    void shouldTreatMissingBusinessDataAsSuccessfulExecution() throws Exception {
        OrderTool order = new OrderTool();
        LogisticsTool logistics = new LogisticsTool();
        var results = executor.execute(List.of(
                new ToolCall("order-call", order.name(), "{\"orderId\":\"99999\"}"),
                new ToolCall("logistics-call", logistics.name(), "{\"trackingNo\":\"missing\"}")),
                new ToolRegistry(List.of(order, logistics)));

        assertThat(results).extracting(result -> result.callId())
                .containsExactly("order-call", "logistics-call");
        for (var result : results) {
            var payload = mapper.readTree(result.output());
            assertThat(payload.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(payload.path("data").path("found").isBoolean()).isTrue();
            assertThat(payload.path("data").path("found").asBoolean()).isFalse();
            assertThat(payload.path("error").isNull()).isTrue();
        }
    }

    @Test
    void shouldKeepBatchResultOrderAndContinueAfterArgumentError() throws Exception {
        OrderTool order = spy(new OrderTool());
        RecordingTool next = new RecordingTool("next_query", "下一项结果");
        var results = executor.execute(List.of(
                new ToolCall("bad-call", order.name(), "{}"),
                new ToolCall("good-call", next.name(), "{}")),
                new ToolRegistry(List.of(order, next)));

        assertThat(results).extracting(result -> result.callId()).containsExactly("bad-call", "good-call");
        assertArgumentError(results.get(0).output(), "orderId必须是非空字符串");
        var success = mapper.readTree(results.get(1).output());
        assertThat(success.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(success.path("data").asText()).isEqualTo("下一项结果");
        verify(order, never()).queryOrder(anyString());
        assertThat(next.executions()).hasValue(1);
    }

    @Test
    void shouldPropagateUnexpectedBusinessException() {
        OrderTool tool = spy(new OrderTool());
        // 普通IllegalArgumentException不能被误认为模型参数错误而吞掉。
        IllegalArgumentException failure = new IllegalArgumentException("模拟业务内部异常");
        doThrow(failure).when(tool).queryOrder("10001");

        assertThatThrownBy(() -> executor.execute(
                List.of(new ToolCall("call-1", tool.name(), "{\"orderId\":\"10001\"}")),
                new ToolRegistry(List.of(tool)))).isSameAs(failure);
    }

    private void assertArgumentError(String output, String message) throws Exception {
        var payload = mapper.readTree(output);
        assertThat(payload.path("status").asText()).isEqualTo("ERROR");
        assertThat(payload.path("data").isNull()).isTrue();
        assertThat(payload.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(payload.path("error").path("message").asText()).isEqualTo(message);
    }

}
