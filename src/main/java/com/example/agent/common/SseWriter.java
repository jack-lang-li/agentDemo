package com.example.agent.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public class SseWriter {

    private final OutputStream outputStream;
    private final ObjectMapper objectMapper;

    public SseWriter(
            OutputStream outputStream,
            ObjectMapper objectMapper) {

        this.outputStream = outputStream;
        this.objectMapper = objectMapper;
    }

    public void send(String eventName, Object data) {

        String json;

        try {
            json = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "SSE 数据序列化失败",
                    exception
            );
        }

        String frame =
                "event: " + eventName + "\n"
                        + "data: " + json + "\n\n";

        try {
            outputStream.write(
                    frame.getBytes(StandardCharsets.UTF_8)
            );

            // 关键：每个事件写完立即刷新。
            outputStream.flush();

        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}