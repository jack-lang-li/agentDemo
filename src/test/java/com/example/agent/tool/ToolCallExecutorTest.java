package com.example.agent.tool;

import com.example.agent.client.model.ToolCall;
import com.example.agent.support.RecordingTool;
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

/** 验证业务提供的工具集合与不同返回类型，工具名称无需修改公共枚举。 */
class ToolCallExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallExecutor executor = new ToolCallExecutor(mapper);

    @Test
    void shouldSerializeBusinessDtoAndListResults() throws Exception {
        RecordingTool stock = new RecordingTool("lookup_inventory", new Stock("sku-1", 12));
        RecordingTool tags = new RecordingTool("list_tags", List.of("tag-a", "tag-b"));
        var results = executor.execute(List.of(
                new ToolCall("call-1", stock.name(), "{}"), new ToolCall("call-2", tags.name(), "{}")),
                new ToolRegistry(List.of(stock, tags)));

        assertThat(results).extracting(result -> result.callId()).containsExactly("call-1", "call-2");
        var stockResult = mapper.readTree(results.get(0).output());
        var tagsResult = mapper.readTree(results.get(1).output());
        assertThat(stockResult.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(stockResult.path("error").isNull()).isTrue();
        assertThat(stockResult.path("data").path("sku").asText()).isEqualTo("sku-1");
        assertThat(stockResult.path("data").path("quantity").asInt()).isEqualTo(12);
        assertThat(tagsResult.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(tagsResult.path("error").isNull()).isTrue();
        assertThat(tagsResult.path("data").get(1).asText()).isEqualTo("tag-b");
        assertThat(stock.executions()).hasValue(1);
        assertThat(tags.executions()).hasValue(1);
    }

    @Test
    void shouldValidateWholeBatchScopeBeforeExecutingFirstTool() {
        RecordingTool allowed = new RecordingTool("allowed", "结果");

        assertThatThrownBy(() -> executor.execute(List.of(new ToolCall("call-1", "allowed", "{}"),
                        new ToolCall("call-2", "not_provided", "{}")), new ToolRegistry(List.of(allowed))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持的工具");
        assertThat(allowed.executions()).hasValue(0);
    }

    @Test
    void shouldRejectDuplicateCallIdsBeforeExecutingTools() {
        RecordingTool tool = new RecordingTool("business_query", "结果");

        assertThatThrownBy(() -> executor.execute(List.of(new ToolCall("same-id", tool.name(), "{}"),
                        new ToolCall("same-id", tool.name(), "{}")), new ToolRegistry(List.of(tool))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("call_id重复");
        assertThat(tool.executions()).hasValue(0);
    }

    @Test
    void shouldAllowSameToolNameInIndependentBusinessRequests() throws Exception {
        RecordingTool first = new RecordingTool("lookup_details", "A 的详情");
        RecordingTool second = new RecordingTool("lookup_details", "B 的详情");
        List<ToolCall> calls = List.of(new ToolCall("call-1", "lookup_details", "{}"));

        var firstResult = executor.execute(calls, new ToolRegistry(List.of(first))).get(0);
        var secondResult = executor.execute(calls, new ToolRegistry(List.of(second))).get(0);
        assertThat(mapper.readTree(firstResult.output()).path("data").asText()).isEqualTo("A 的详情");
        assertThat(mapper.readTree(secondResult.output()).path("data").asText()).isEqualTo("B 的详情");
        assertThat(first.executions()).hasValue(1);
        assertThat(second.executions()).hasValue(1);
    }

    @Test
    void shouldRejectDuplicateToolNamesWithinTheSameBusinessRequest() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                new RecordingTool("duplicate", "A"), new RecordingTool("duplicate", "B"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("工具名称重复");
    }

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

    @Test
    void shouldPropagateSerializationFailureInsteadOfReturningArgumentError() {
        RecordingTool tool = new RecordingTool("broken_result", new BrokenResult());

        assertThatThrownBy(() -> executor.execute(
                List.of(new ToolCall("call-1", tool.name(), "{}")), new ToolRegistry(List.of(tool))))
                .isInstanceOf(IllegalStateException.class).hasMessage("工具结果序列化失败");
        assertThat(tool.executions()).hasValue(1);
    }

    private void assertArgumentError(String output, String message) throws Exception {
        var payload = mapper.readTree(output);
        assertThat(payload.path("status").asText()).isEqualTo("ERROR");
        assertThat(payload.path("data").isNull()).isTrue();
        assertThat(payload.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(payload.path("error").path("message").asText()).isEqualTo(message);
    }

    public static class BrokenResult {
        public String getValue() {
            throw new IllegalStateException("模拟结果读取失败");
        }
    }

    private record Stock(String sku, int quantity) {
    }
}
