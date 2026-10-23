package com.example.agent.client;

import com.example.agent.config.ModelProperties;
import com.example.agent.tool.OrderTool;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
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
class ModelClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OrderTool orderTool;
    private ModelClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        orderTool = mock(OrderTool.class);
        ModelProperties properties = new ModelProperties();
        properties.setBaseUrl("http://model.test");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        client = new ModelClient(properties, builder.build(), orderTool, objectMapper);
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

        assertThat(client.chat("查询订单")).isEqualTo("请提供订单编号");
        verifyNoInteractions(orderTool);
    }

    @Test
    void shouldKeepHistoryAndReturnToolResultsAcrossMultipleRounds() {
        when(orderTool.queryOrder("10001")).thenReturn("订单10001已发货");
        when(orderTool.queryOrder("10002")).thenReturn("未找到订单10002");
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
                .andExpect(jsonPath("$.input[3].output").value("订单10001已发货"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess(toolResponse("call-2", "query_order", "{\"orderId\":\"10002\"}")
                        .toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(6))
                .andExpect(jsonPath("$.input[3].call_id").value("call-1"))
                .andExpect(jsonPath("$.input[5].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[5].call_id").value("call-2"))
                .andExpect(jsonPath("$.input[5].output").value("未找到订单10002"))
                .andRespond(withSuccess(answerResponse("订单查询完成"), MediaType.APPLICATION_JSON));

        assertThat(client.chat("查询两个订单")).isEqualTo("订单查询完成");
        var ordered = inOrder(orderTool);
        ordered.verify(orderTool).queryOrder("10001");
        ordered.verify(orderTool).queryOrder("10002");
        verifyNoMoreInteractions(orderTool);
    }

    @Test
    void shouldExecuteAllToolCallsInOneResponse() {
        when(orderTool.queryOrder("10001")).thenReturn("已发货");
        when(orderTool.queryOrder("10002")).thenReturn("未找到订单");
        ObjectNode response = toolResponse("call-1", "query_order", "{\"orderId\":\"10001\"}");
        response.withArray("output").add(toolResponse("call-2", "query_order", "{\"orderId\":\"10002\"}")
                .path("output").get(0));
        expectRequest().andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(5))
                .andExpect(jsonPath("$.input[3].call_id").value("call-1"))
                .andExpect(jsonPath("$.input[3].output").value("已发货"))
                .andExpect(jsonPath("$.input[4].call_id").value("call-2"))
                .andExpect(jsonPath("$.input[4].output").value("未找到订单"))
                .andRespond(withSuccess(answerResponse("查询完成"), MediaType.APPLICATION_JSON));

        assertThat(client.chat("查询两个订单")).isEqualTo("查询完成");
        verify(orderTool).queryOrder("10001");
        verify(orderTool).queryOrder("10002");
        verifyNoMoreInteractions(orderTool);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"in_progress", "incomplete", "failed"})
    void shouldRejectUnfinishedResponseBeforeExecutingTools(String status) {
        ObjectNode response = toolResponse("call-1", "query_order", "{\"orderId\":\"10001\"}");
        response.put("status", status);
        expectRequest().andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("模型响应未完成");
        verifyNoInteractions(orderTool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"output\":null}", "{\"output\":{}}"})
    void shouldRejectResponseWithoutOutputArray(String response) {
        expectRequest().andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少有效的output数组");
        verifyNoInteractions(orderTool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void shouldRejectBlankFinalAnswer(String answer) {
        expectRequest().andRespond(withSuccess(answerResponse(answer), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未返回有效回答");
        verifyNoInteractions(orderTool);
    }

    @Test
    void shouldRejectUnsupportedTool() {
        expectRequest().andRespond(withSuccess(toolResponse("call-1", "unknown_tool", "{}").toString(),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不支持的工具");
        verifyNoInteractions(orderTool);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void shouldRejectMissingCallId(String callId) {
        expectRequest().andRespond(withSuccess(toolResponse(callId, "query_order", "{\"orderId\":\"10001\"}")
                .toString(), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺少call_id");
        verifyNoInteractions(orderTool);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-json", "null", "[]", "{}", "{\"orderId\":null}",
            "{\"orderId\":10001}", "{\"orderId\":\"\"}", "{\"orderId\":\"   \"}"})
    void shouldRejectInvalidToolArguments(String arguments) {
        expectRequest().andRespond(withSuccess(toolResponse("call-1", "query_order", arguments).toString(),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("查询订单")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(orderTool);
    }

    @Test
    void shouldStopAtFiveRoundsWithoutExecutingLastRoundTools() {
        when(orderTool.queryOrder("10001")).thenReturn("已发货");
        for (int round = 1; round <= 5; round++) {
            expectRequest().andRespond(withSuccess(toolResponse("call-" + round, "query_order",
                    "{\"orderId\":\"10001\"}").toString(), MediaType.APPLICATION_JSON));
        }

        assertThatThrownBy(() -> client.chat("查询订单"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("达到模型调用轮数上限");
        verify(orderTool, times(4)).queryOrder("10001");
        verifyNoMoreInteractions(orderTool);
    }

    @Test
    void shouldAcceptFinalAnswerOnFifthRound() {
        when(orderTool.queryOrder("10001")).thenReturn("已发货");
        for (int round = 1; round < 5; round++) {
            expectRequest().andRespond(withSuccess(toolResponse("call-" + round, "query_order",
                    "{\"orderId\":\"10001\"}").toString(), MediaType.APPLICATION_JSON));
        }
        expectRequest().andRespond(withSuccess(answerResponse("最终回答"), MediaType.APPLICATION_JSON));

        assertThat(client.chat("查询订单")).isEqualTo("最终回答");
        verify(orderTool, times(4)).queryOrder("10001");
        verifyNoMoreInteractions(orderTool);
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://model.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
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
