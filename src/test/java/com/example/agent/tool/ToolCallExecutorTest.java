package com.example.agent.tool;

import com.example.agent.client.model.ToolCall;
import com.example.agent.support.RecordingTool;
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
        assertThat(mapper.readTree(results.get(0).output()).path("sku").asText()).isEqualTo("sku-1");
        assertThat(mapper.readTree(results.get(0).output()).path("quantity").asInt()).isEqualTo(12);
        assertThat(mapper.readTree(results.get(1).output()).get(1).asText()).isEqualTo("tag-b");
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
    void shouldAllowSameToolNameInIndependentBusinessRequests() {
        RecordingTool first = new RecordingTool("lookup_details", "A 的详情");
        RecordingTool second = new RecordingTool("lookup_details", "B 的详情");
        List<ToolCall> calls = List.of(new ToolCall("call-1", "lookup_details", "{}"));

        assertThat(executor.execute(calls, new ToolRegistry(List.of(first))).get(0).output())
                .isEqualTo("\"A 的详情\"");
        assertThat(executor.execute(calls, new ToolRegistry(List.of(second))).get(0).output())
                .isEqualTo("\"B 的详情\"");
        assertThat(first.executions()).hasValue(1);
        assertThat(second.executions()).hasValue(1);
    }

    @Test
    void shouldRejectDuplicateToolNamesWithinTheSameBusinessRequest() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                new RecordingTool("duplicate", "A"), new RecordingTool("duplicate", "B"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("工具名称重复");
    }

    private record Stock(String sku, int quantity) {
    }
}
