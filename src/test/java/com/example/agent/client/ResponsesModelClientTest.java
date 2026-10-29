package com.example.agent.client;

import com.example.agent.client.model.ModelInput;
import com.example.agent.client.model.ModelTool;
import com.example.agent.client.responses.ResponsesModelClient;
import com.example.agent.client.responses.ResponsesModelCodec;
import com.example.agent.client.responses.ResponsesStreamReader;
import com.example.agent.config.ModelProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.agent.support.ModelFixtures.answer;
import static com.example.agent.support.ModelFixtures.completedFrame;
import static com.example.agent.support.ModelFixtures.proposal;
import static com.example.agent.support.ModelFixtures.responseJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** 验证普通业务无需工具、Agent 或 HTTP Controller，即可使用公共模型接口。 */
class ResponsesModelClientTest {

    private MockRestServiceServer server;
    private ModelClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ModelProperties properties = new ModelProperties();
        properties.setBaseUrl("http://model.test");
        properties.setApiKey("test-key");
        properties.setModel("test-model");
        ResponsesModelCodec codec = new ResponsesModelCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        client = new ResponsesModelClient(properties, builder.build(), builder.build(), codec,
                new ResponsesStreamReader(new com.fasterxml.jackson.databind.ObjectMapper(), codec));
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void shouldGenerateTextWithoutAdvertisingAnyBusinessTools() {
        expectRequest()
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andExpect(jsonPath("$.input[0].content").value("总结这段文字"))
                .andExpect(jsonPath("$.tools.length()").value(0))
                .andExpect(jsonPath("$.tool_choice").doesNotExist())
                .andExpect(jsonPath("$.stream").doesNotExist())
                .andRespond(withSuccess(responseJson(answer("摘要结果")), MediaType.APPLICATION_JSON));

        assertThat(client.chat("总结这段文字")).isEqualTo("摘要结果");
    }

    @Test
    void shouldKeepBusinessInstructionsSeparateFromUserMessage() {
        expectRequest()
                .andExpect(jsonPath("$.input.length()").value(2))
                .andExpect(jsonPath("$.input[0].role").value("system"))
                .andExpect(jsonPath("$.input[0].content").value("你是摘要助手"))
                .andExpect(jsonPath("$.input[1].role").value("user"))
                .andExpect(jsonPath("$.input[1].content").value("待总结内容"))
                .andRespond(withSuccess(responseJson(answer("摘要")), MediaType.APPLICATION_JSON));

        assertThat(client.chat("你是摘要助手", "待总结内容")).isEqualTo("摘要");
    }

    @Test
    void shouldReturnToolProposalAfterExactlyOneModelRequest() {
        ModelTool tool = new ModelTool();
        tool.setType("function");
        tool.setName("lookup_inventory");
        tool.setDescription("查询当前业务的库存");
        tool.setParameters(Map.of("type", "object"));
        expectRequest()
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].name").value("lookup_inventory"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andRespond(withSuccess(responseJson(proposal("call-1", "lookup_inventory", "{}")),
                        MediaType.APPLICATION_JSON));

        var result = client.generate(ModelInput.text("查库存").withTools(List.of(tool)));

        assertThat(result.hasToolCalls()).isTrue();
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.name()).isEqualTo("lookup_inventory");
            assertThat(call.callId()).isEqualTo("call-1");
        });
    }

    @Test
    void shouldRejectToolProposalWhenCallerOnlyRequestsFinalText() {
        expectRequest().andRespond(withSuccess(responseJson(proposal("call-1", "unknown", "{}")),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chat("你好"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("模型返回了工具调用");
    }

    @Test
    void shouldStreamRealDeltasAndReturnCompletedText() {
        String stream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"你\"}\n\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"好\"}\n\n"
                + completedFrame(answer("你好"));
        expectRequest()
                .andExpect(header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(jsonPath("$.stream").value(true))
                .andExpect(jsonPath("$.tools.length()").value(0))
                .andRespond(withSuccess(stream, MediaType.TEXT_EVENT_STREAM));
        List<String> deltas = new ArrayList<>();

        assertThat(client.chatStream("问题", deltas::add)).isEqualTo("你好");
        assertThat(deltas).containsExactly("你", "好");
    }

    @Test
    void shouldRejectNonSseContentTypeForStreamingRequest() {
        expectRequest().andRespond(withSuccess(responseJson(answer("完整结果")), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.chatStream("问题", text -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("未返回 text/event-stream");
    }

    @Test
    void shouldRejectFailedHttpStatusBeforeReadingStreamingEvents() {
        expectRequest().andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .contentType(MediaType.TEXT_EVENT_STREAM).body(completedFrame(answer("不能当成成功"))));

        assertThatThrownBy(() -> client.chatStream("问题", text -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 状态码：502");
    }

    private ResponseActions expectRequest() {
        return server.expect(requestTo("http://model.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
