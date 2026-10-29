package com.example.agent.business;

import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.DefaultAgentRunner;
import com.example.agent.agent.AgentRequest;

import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelCodec;
import com.example.agent.llm.provider.responses.ResponsesStreamReader;

import com.example.agent.llm.provider.responses.config.ModelProperties;
import com.example.agent.business.order.tool.OrderTool;
import com.example.agent.agent.tool.ToolCallExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 使用模拟 HTTP 响应验证模型协议和 Agent 循环，不访问外部模型服务。
 * JSON 样例和断言保留协议原值，独立校验枚举值的实际序列化结果。
 */
class AgentRunnerProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OrderTool orderTool;
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        // 使用真实工具定义与参数校验，只替换订单查询结果，适配当前 ToolRegistry 契约。
        orderTool = spy(new OrderTool());
        ModelProperties properties = new ModelProperties();
        properties.setBaseUrl("http://model.test");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        ResponsesModelCodec codec = new ResponsesModelCodec(objectMapper);
        ModelClient modelClient = new ResponsesModelClient(properties, builder.build(), builder.build(),
                codec, new ResponsesStreamReader(objectMapper, codec));
        runner = new DefaultAgentRunner(modelClient, new ToolCallExecutor(objectMapper));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void shouldSendProtocolValuesAndReturnAnswerWithoutCallingTools() {
        expectRequest()
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andExpect(jsonPath("$.input[0].content").value("查询订单"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].name").value("query_order"))
                .andExpect(jsonPath("$.tools[0].parameters.type").value("object"))
                .andExpect(jsonPath("$.tools[0].parameters.properties.orderId.type").value("string"))
                .andExpect(jsonPath("$.tools[0].parameters.required[0]").value("orderId"))
                .andExpect(jsonPath("$.tools[0].parameters.additionalProperties").value(false))
                .andRespond(withSuccess(answerResponse("请提供订单编号"), MediaType.APPLICATION_JSON));

        assertThat(chat("查询订单")).isEqualTo("请提供订单编号");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @Test
    void shouldKeepHistoryAndReturnToolResultsAcrossMultipleRounds() {
        doReturn(Map.of("summary", "订单10001已发货")).when(orderTool).queryOrder("10001");
        doReturn(Map.of("summary", "未找到订单10002")).when(orderTool).queryOrder("10002");
        ObjectNode firstResponse = toolResponse("call-1", "query_order", "{\"orderId\":\"10001\"}");
        firstResponse.withArray("output").insert(0,
                objectMapper.createObjectNode().put("type", "reasoning").put("id", "reason-1"));
        expectRequest().andRespond(withSuccess(firstResponse.toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(4))
                .andExpect(jsonPath("$.input[0].content").value("查询两个订单"))
                .andExpect(jsonPath("$.input[1].id").value("reason-1"))
                .andExpect(jsonPath("$.input[2].type").value("function_call"))
                .andExpect(jsonPath("$.input[3].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[3].call_id").value("call-1"))
                .andExpect(successfulToolOutput(3, "订单10001已发货"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess(toolResponse("call-2", "query_order", "{\"orderId\":\"10002\"}")
                        .toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(6))
                .andExpect(jsonPath("$.input[3].call_id").value("call-1"))
                .andExpect(jsonPath("$.input[5].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[5].call_id").value("call-2"))
                .andExpect(successfulToolOutput(5, "未找到订单10002"))
                .andRespond(withSuccess(answerResponse("订单查询完成"), MediaType.APPLICATION_JSON));

        assertThat(chat("查询两个订单")).isEqualTo("订单查询完成");
        var ordered = inOrder(orderTool);
        ordered.verify(orderTool).queryOrder("10001");
        ordered.verify(orderTool).queryOrder("10002");
        verify(orderTool, times(2)).queryOrder(anyString());
    }

    @Test
    void shouldExecuteAllToolCallsInOneResponse() {
        doReturn(Map.of("summary", "已发货")).when(orderTool).queryOrder("10001");
        doReturn(Map.of("summary", "未找到订单")).when(orderTool).queryOrder("10002");
        ObjectNode response = toolResponse("call-1", "query_order", "{\"orderId\":\"10001\"}");
        response.withArray("output").add(toolResponse("call-2", "query_order", "{\"orderId\":\"10002\"}")
                .path("output").get(0));
        expectRequest().andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(5))
                .andExpect(jsonPath("$.input[3].call_id").value("call-1"))
                .andExpect(successfulToolOutput(3, "已发货"))
                .andExpect(jsonPath("$.input[4].call_id").value("call-2"))
                .andExpect(successfulToolOutput(4, "未找到订单"))
                .andRespond(withSuccess(answerResponse("查询完成"), MediaType.APPLICATION_JSON));

        assertThat(chat("查询两个订单")).isEqualTo("查询完成");
        verify(orderTool).queryOrder("10001");
        verify(orderTool).queryOrder("10002");
        verify(orderTool, times(2)).queryOrder(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"in_progress", "incomplete", "failed"})
    void shouldRejectUnfinishedResponseBeforeExecutingTools(String status) {
        ObjectNode response = toolResponse("call-1", "query_order", "{\"orderId\":\"10001\"}");
        response.put("status", status);
        expectRequest().andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("模型响应未完成");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"output\":null}", "{\"output\":{}}"})
    void shouldRejectResponseWithoutOutputArray(String response) {
        expectRequest().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少有效的output数组");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectBlankFinalAnswer(String answer) {
        expectRequest().andRespond(withSuccess(answerResponse(answer), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未返回有效回答");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @Test
    void shouldRejectUnsupportedTool() {
        expectRequest().andRespond(withSuccess(toolResponse("call-1", "unknown_tool", "{}").toString(),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持的工具");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRejectMissingCallId(String callId) {
        expectRequest().andRespond(withSuccess(toolResponse(callId, "query_order", "{\"orderId\":\"10001\"}")
                .toString(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺少call_id");
        verify(orderTool, never()).queryOrder(anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "null", "[]"})
    void shouldRejectInvalidToolArgumentStructure(String arguments) {
        expectRequest().andRespond(withSuccess(toolResponse("call-1", "query_order", arguments).toString(),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> chat("查询订单")).isInstanceOf(IllegalArgumentException.class);
        verify(orderTool, never()).queryOrder(anyString());
    }

    @Test
    void shouldContinueAfterArgumentErrorAndReturnAnswer() {
        // 模拟三次上游响应，只验证程序的反馈闭环，不依赖真实模型碰巧生成错误参数。
        expectRequest().andRespond(withSuccess(
                toolResponse("bad-call", "query_order", "{\"orderId\":10001}").toString(),
                MediaType.APPLICATION_JSON));

        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(3))
                .andExpect(jsonPath("$.input[1].call_id").value("bad-call"))
                .andExpect(jsonPath("$.input[2].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[2].call_id").value("bad-call"))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    JsonNode failure = objectMapper.readTree(body.path("input").path(2).path("output").asText());
                    assertThat(failure.path("status").asText()).isEqualTo("ERROR");
                    assertThat(failure.path("data").isNull()).isTrue();
                    assertThat(failure.path("error").path("code").asText()).isEqualTo("INVALID_ARGUMENTS");
                    assertThat(failure.path("error").path("message").asText())
                            .isEqualTo("orderId必须是非空字符串");
                    verify(orderTool, never()).queryOrder(anyString());
                })
                .andRespond(withSuccess(
                        toolResponse("corrected-call", "query_order", "{\"orderId\":\"10001\"}").toString(),
                        MediaType.APPLICATION_JSON));

        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(5))
                .andExpect(jsonPath("$.input[2].call_id").value("bad-call"))
                .andExpect(jsonPath("$.input[3].call_id").value("corrected-call"))
                .andExpect(jsonPath("$.input[4].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[4].call_id").value("corrected-call"))
                .andExpect(request -> {
                    JsonNode body = objectMapper.readTree(
                            ((MockClientHttpRequest) request).getBodyAsString());
                    // 失败历史不能被成功结果覆盖，两次结果分别关联各自的调用编号。
                    JsonNode failure = objectMapper.readTree(body.path("input").path(2).path("output").asText());
                    JsonNode success = objectMapper.readTree(body.path("input").path(4).path("output").asText());
                    assertThat(failure.path("status").asText()).isEqualTo("ERROR");
                    assertThat(success.path("status").asText()).isEqualTo("SUCCESS");
                    assertThat(success.path("error").isNull()).isTrue();
                    assertThat(success.path("data").path("found").asBoolean()).isTrue();
                    assertThat(success.path("data").path("orderId").asText()).isEqualTo("10001");
                })
                .andRespond(withSuccess(answerResponse("订单10001已发货"), MediaType.APPLICATION_JSON));

        assertThat(chat("查询订单10001的状态")).isEqualTo("订单10001已发货");
        verify(orderTool, times(1)).queryOrder("10001");
        verify(orderTool, times(1)).queryOrder(anyString());
    }

    @Test
    void shouldStopAtRoundLimitWhenModelRepeatsInvalidArguments() {
        expectRequest().andRespond(withSuccess(
                toolResponse("bad-1", "query_order", "{}").toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input[2].call_id").value("bad-1"))
                .andRespond(withSuccess(toolResponse("bad-2", "query_order", "{}").toString(),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> runner.run(new AgentRequest(null, "查询订单", List.of(orderTool), 2)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("轮数上限");
        // 第一轮尝试执行并反馈参数错误；最后一轮已无法回传结果，不再执行。
        verify(orderTool, times(1)).execute(any(JsonNode.class));
        verify(orderTool, never()).queryOrder(anyString());
    }

    @Test
    void shouldStopAtFiveRoundsWithoutExecutingLastRoundTools() {
        doReturn(Map.of("summary", "已发货")).when(orderTool).queryOrder("10001");
        for (int round = 1; round <= 5; round++) {
            expectRequest().andRespond(withSuccess(toolResponse("call-" + round, "query_order",
                    "{\"orderId\":\"10001\"}").toString(), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("达到模型调用轮数上限");
        verify(orderTool, times(4)).queryOrder("10001");
        verify(orderTool, times(4)).queryOrder(anyString());
    }

    @Test
    void shouldAcceptFinalAnswerOnFifthRound() {
        doReturn(Map.of("summary", "已发货")).when(orderTool).queryOrder("10001");
        for (int round = 1; round < 5; round++) {
            expectRequest().andRespond(withSuccess(toolResponse("call-" + round, "query_order",
                    "{\"orderId\":\"10001\"}").toString(), MediaType.APPLICATION_JSON));
        }
        expectRequest().andRespond(withSuccess(answerResponse("最终回答"), MediaType.APPLICATION_JSON));

        assertThat(chat("查询订单")).isEqualTo("最终回答");
        verify(orderTool, times(4)).queryOrder("10001");
        verify(orderTool, times(4)).queryOrder(anyString());
    }

    private String chat(String message) {
        // 本测试业务只开放订单工具，公共模型客户端和 Agent 不自动补充全局工具。
        return runner.run(new AgentRequest(message, List.of(orderTool)));
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://model.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /** 解析output内部的JSON，独立验证结果契约，不依赖属性顺序或生产包装工厂。 */
    private RequestMatcher successfulToolOutput(int inputIndex, String summary) {
        return request -> {
            JsonNode body = objectMapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
            JsonNode item = body.path("input").path(inputIndex);
            assertThat(item.path("type").asText()).isEqualTo("function_call_output");
            assertThat(item.path("output").isTextual()).isTrue();
            JsonNode actual = objectMapper.readTree(item.path("output").asText());
            ObjectNode expected = objectMapper.createObjectNode().put("status", "SUCCESS");
            expected.set("data", objectMapper.valueToTree(Map.of("summary", summary)));
            expected.putNull("error");
            assertThat(actual).isEqualTo(expected);
        };
    }

    private ObjectNode toolResponse(String callId, String name, String arguments) {
        ObjectNode response = objectMapper.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments);
        return response;
    }

    private String answerResponse(String answer) {
        ObjectNode response = objectMapper.createObjectNode().put("status", "completed");
        response.putArray("output").addObject().put("type", "message")
                .putArray("content").addObject().put("type", "output_text").put("text", answer);
        return response.toString();
    }
}
