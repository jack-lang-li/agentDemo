package com.example.agent.business;

import com.example.agent.agent.tool.ToolCallExecutor;
import com.example.agent.agent.tool.ToolRegistry;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.business.logistics.tool.LogisticsTool;
import com.example.agent.business.order.application.OrderQueryService;
import com.example.agent.business.logistics.application.LogisticsQueryService;
import com.example.agent.business.security.authorization.AuthorizedAgentTool;
import com.example.agent.business.security.authorization.CallerAuthorization;
import com.example.agent.business.security.authorization.CallerAwareAgentTool;
import com.example.agent.agent.tool.AgentTool;
import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;

import com.example.agent.llm.api.model.ToolCall;
import com.example.agent.testsupport.RecordingTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 原工具执行测试中的订单、物流场景，避免通用核心反向依赖业务工具。 */
class BusinessToolCallExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolCallExecutor executor = new ToolCallExecutor(mapper);
    private final OrderQueryService orderQueries = mock(OrderQueryService.class);
    private final LogisticsQueryService logisticsQueries = mock(LogisticsQueryService.class);
    private final CallerAuthorization authorization = new CallerAuthorization(
            new CallerContext("tenant-test", CallerType.USER, "user-test"),
            Set.of("order:query", "logistics:query"));

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"orderId\":null}", "{\"orderId\":10001}",
            "{\"orderId\":\"\"}", "{\"orderId\":\"   \"}"})
    void shouldReturnOrderArgumentErrorWithoutQuerying(String arguments) throws Exception {
        OrderTool tool = new OrderTool(orderQueries);
        var results = executor.execute(List.of(new ToolCall("call-1", tool.name(), arguments)),
                new ToolRegistry(List.of(bound(tool, "order:query"))));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
        assertArgumentError(results.get(0).output(), "orderId必须是非空字符串");
        verifyNoInteractions(orderQueries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"trackingNo\":null}", "{\"trackingNo\":10001}",
            "{\"trackingNo\":\"\"}", "{\"trackingNo\":\"   \"}"})
    void shouldReturnLogisticsArgumentErrorWithoutQuerying(String arguments) throws Exception {
        LogisticsTool tool = new LogisticsTool(logisticsQueries);
        var results = executor.execute(List.of(new ToolCall("call-1", tool.name(), arguments)),
                new ToolRegistry(List.of(bound(tool, "logistics:query"))));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).callId()).isEqualTo("call-1");
        assertArgumentError(results.get(0).output(), "trackingNo必须是非空字符串");
        verifyNoInteractions(logisticsQueries);
    }

    @Test
    void shouldTreatMissingBusinessDataAsSuccessfulExecution() throws Exception {
        OrderTool order = new OrderTool(orderQueries);
        LogisticsTool logistics = new LogisticsTool(logisticsQueries);
        when(orderQueries.query(authorization, "99999")).thenReturn(Map.of("found", false, "orderId", "99999"));
        when(logisticsQueries.query(authorization, "missing"))
                .thenReturn(Map.of("found", false, "trackingNo", "missing"));
        var results = executor.execute(List.of(
                new ToolCall("order-call", order.name(), "{\"orderId\":\"99999\"}"),
                new ToolCall("logistics-call", logistics.name(), "{\"trackingNo\":\"missing\"}")),
                new ToolRegistry(List.of(bound(order, "order:query"), bound(logistics, "logistics:query"))));

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
        OrderTool order = new OrderTool(orderQueries);
        RecordingTool next = new RecordingTool("next_query", "下一项结果");
        var results = executor.execute(List.of(
                new ToolCall("bad-call", order.name(), "{}"),
                new ToolCall("good-call", next.name(), "{}")),
                new ToolRegistry(List.of(bound(order, "order:query"), next)));

        assertThat(results).extracting(result -> result.callId()).containsExactly("bad-call", "good-call");
        assertArgumentError(results.get(0).output(), "orderId必须是非空字符串");
        var success = mapper.readTree(results.get(1).output());
        assertThat(success.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(success.path("data").asText()).isEqualTo("下一项结果");
        verifyNoInteractions(orderQueries);
        assertThat(next.executions()).hasValue(1);
    }

    @Test
    void shouldPropagateUnexpectedBusinessException() {
        OrderTool tool = new OrderTool(orderQueries);
        // 普通IllegalArgumentException不能被误认为模型参数错误而吞掉。
        IllegalArgumentException failure = new IllegalArgumentException("模拟业务内部异常");
        doThrow(failure).when(orderQueries).query(authorization, "10001");

        assertThatThrownBy(() -> executor.execute(
                List.of(new ToolCall("call-1", tool.name(), "{\"orderId\":\"10001\"}")),
                new ToolRegistry(List.of(bound(tool, "order:query"))))).isSameAs(failure);
    }

    @Test
    void shouldRejectUnboundBusinessToolsBeforeQuerying() {
        List<AgentTool> unbound = List.of(new OrderTool(orderQueries), new LogisticsTool(logisticsQueries));
        for (AgentTool tool : unbound) {
            assertThatThrownBy(() -> tool.execute(mapper.createObjectNode()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("业务工具尚未绑定服务端授权上下文");
        }
        verifyNoInteractions(orderQueries, logisticsQueries);
    }

    @Test
    void shouldKeepTrustedAuthorizationWhenModelArgumentsContainIdentityClaims() throws Exception {
        OrderTool tool = new OrderTool(orderQueries);
        when(orderQueries.query(authorization, "10001")).thenReturn(Map.of("found", true, "orderId", "10001"));
        var results = executor.execute(List.of(new ToolCall("call-1", tool.name(),
                        "{\"orderId\":\"10001\",\"tenantId\":\"forged\",\"ownerUserId\":\"forged\"}")),
                new ToolRegistry(List.of(bound(tool, "order:query"))));

        assertThat(mapper.readTree(results.get(0).output()).path("data").path("found").asBoolean()).isTrue();
        verify(orderQueries).query(authorization, "10001");
        verifyNoInteractions(logisticsQueries);
    }

    private AgentTool bound(CallerAwareAgentTool tool, String permission) {
        return new AuthorizedAgentTool(tool, authorization, permission);
    }

    private void assertArgumentError(String output, String message) throws Exception {
        var payload = mapper.readTree(output);
        assertThat(payload.path("status").asText()).isEqualTo("ERROR");
        assertThat(payload.path("data").isNull()).isTrue();
        assertThat(payload.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
        assertThat(payload.path("error").path("message").asText()).isEqualTo(message);
    }

}
