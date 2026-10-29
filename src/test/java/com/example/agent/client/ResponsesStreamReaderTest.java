package com.example.agent.client;

import com.example.agent.client.responses.ResponsesModelCodec;
import com.example.agent.client.responses.ResponsesStreamReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.example.agent.support.ModelFixtures.answer;
import static com.example.agent.support.ModelFixtures.completedFrame;
import static com.example.agent.support.ModelFixtures.proposal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在字节分片、事件分帧和失败边界上保护上游流式解析，测试不经过网络。 */
class ResponsesStreamReaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesStreamReader reader = new ResponsesStreamReader(mapper, new ResponsesModelCodec(mapper));

    @Test
    void shouldHandleFragmentedUtf8CrLfMultilineDataAndHeartbeat() throws IOException {
        String stream = ": heartbeat\r\n\r\nevent: response.output_text.delta\r\n"
                + "data: {\"delta\":\r\ndata: \"你好🙂\"}\r\n\r\n"
                + completedFrame(answer("你好🙂")).replace("\n", "\r\n");
        ByteArrayInputStream fragmented = new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        List<String> deltas = new ArrayList<>();

        assertThat(reader.read(fragmented, deltas::add).requireText()).isEqualTo("你好🙂");
        assertThat(deltas).containsExactly("你好🙂");
    }

    @Test
    void shouldIgnoreReasoningAndPartialToolArgumentsUntilCompletedResult() throws IOException {
        String stream = "data: {\"type\":\"response.reasoning_text.delta\",\"delta\":\"内部推理\"}\n\n"
                + "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"id\\\":\"}\n\n"
                + completedFrame(proposal("call-1", "business_tool", "{\"id\":1}"));
        List<String> deltas = new ArrayList<>();

        var result = reader.read(bytes(stream), deltas::add);

        assertThat(deltas).isEmpty();
        assertThat(result.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.arguments()).isEqualTo("{\"id\":1}"));
    }

    @Test
    void shouldHandleRefusalTextInBothDeltaAndCompletedResponse() throws IOException {
        String stream = "data: {\"type\":\"response.refusal.delta\",\"delta\":\"无法回答\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":["
                + "{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"无法回答\"}]}]}}\n\n";
        List<String> deltas = new ArrayList<>();

        assertThat(reader.read(bytes(stream), deltas::add).requireText()).isEqualTo("无法回答");
        assertThat(deltas).containsExactly("无法回答");
    }

    @ParameterizedTest
    @ValueSource(strings = {"response.failed", "response.incomplete", "error"})
    void shouldFailForTerminalErrorAfterPartialOutput(String type) {
        String stream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分正文\"}\n\n"
                + "data: {\"type\":\"" + type + "\"}\n\n";
        List<String> deltas = new ArrayList<>();

        assertThatThrownBy(() -> reader.read(bytes(stream), deltas::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(type);
        assertThat(deltas).containsExactly("部分正文");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "data: [DONE]\n\n"})
    void shouldRejectEofWithoutCompletedResponseEvenIfDoneMarkerWasPresent(String tail) {
        String stream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分正文\"}\n\n" + tail;

        assertThatThrownBy(() -> reader.read(bytes(stream), text -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有收到 response.completed");
    }

    @Test
    void shouldRejectMalformedDeltaInsteadOfConvertingItToText() {
        assertThatThrownBy(() -> reader.read(bytes(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":123}\n\n"), text -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("文本增量不是字符串");
    }

    @Test
    void shouldRejectCompletedEventWithoutValidFullResponse() {
        assertThatThrownBy(() -> reader.read(bytes(
                "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"), text -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少有效的output数组");
    }

    @Test
    void shouldPropagateCallbackFailureWithoutConsumingRemainingEvents() {
        String stream = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"第一段\"}\n\n"
                + completedFrame(answer("完整正文"));
        UncheckedIOException failure = new UncheckedIOException(new IOException("模拟连接断开"));

        assertThatThrownBy(() -> reader.read(bytes(stream), text -> { throw failure; })).isSameAs(failure);
    }

    @Test
    void shouldAcceptCompletedLastFrameWithoutTrailingBlankLine() throws IOException {
        assertThat(reader.read(bytes(completedFrame(answer("结果")).stripTrailing()), text -> { })
                .requireText()).isEqualTo("结果");
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
