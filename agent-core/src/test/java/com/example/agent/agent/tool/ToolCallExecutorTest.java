package com.example.agent.agent.tool;

import com.example.agent.llm.api.model.ToolCall;
import com.example.agent.testsupport.RecordingTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
