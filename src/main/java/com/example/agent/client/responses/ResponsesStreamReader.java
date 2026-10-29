package com.example.agent.client.responses;

import com.example.agent.client.model.ModelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Responses 上游 SSE 解析器，不依赖 HTTP Controller、业务工具或 Agent 循环。
 *
 * <p>使用 UTF-8 字符流处理跨网络分片的中文，按空行分帧，支持 CRLF、多行 data 和注释心跳。
 * 只回调正文与拒答增量，工具参数和 reasoning 增量不作为正文转发。
 * 必须取得完整 response.completed；提前 EOF 和 failed/incomplete/error 均为失败。</p>
 */
@Component
@RequiredArgsConstructor
public class ResponsesStreamReader {

    private final ObjectMapper objectMapper;
    private final ResponsesModelCodec codec;

    /** 在输入流仍有效时完成解析；调用方负责确保该方法位于 HTTP exchange 回调内部。 */
    public ModelResult read(InputStream inputStream, Consumer<String> onDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String eventName = "";
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("流式请求已中断");
                }
                if (line.isEmpty()) {
                    if (hasData) {
                        ModelResult completed = consume(eventName, data.toString(), onDelta);
                        if (completed != null) {
                            return completed;
                        }
                    }
                    eventName = "";
                    data.setLength(0);
                    hasData = false;
                } else if (line.startsWith(":")) {
                    // 心跳不参与 JSON 数据拼接。
                    continue;
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    if (hasData) {
                        data.append('\n');
                    }
                    data.append(value);
                    hasData = true;
                }
            }

            // 保留原有兼容行为：最后一帧没有空行时，仍尝试解析完整完成事件。
            if (hasData) {
                ModelResult completed = consume(eventName, data.toString(), onDelta);
                if (completed != null) {
                    return completed;
                }
            }
        }
        throw new IllegalStateException("模型连接已结束，但没有收到 response.completed");
    }

    private ModelResult consume(String eventName, String data, Consumer<String> onDelta) throws IOException {
        // [DONE] 不是 Responses 成功完成的依据，仍需等待 response.completed。
        if (data.isBlank() || "[DONE]".equals(data)) {
            return null;
        }
        JsonNode event = objectMapper.readTree(data);
        if (event == null || !event.isObject()) {
            throw new IllegalStateException("模型返回了无效的 SSE 数据");
        }
        String type = event.path("type").asText(eventName);
        switch (type) {
            case "response.output_text.delta", "response.refusal.delta" -> {
                JsonNode delta = event.path("delta");
                if (!delta.isTextual()) {
                    throw new IllegalStateException("模型文本增量不是字符串");
                }
                if (!delta.asText().isEmpty()) {
                    onDelta.accept(delta.asText());
                }
            }
            case "response.completed" -> {
                return codec.decode(event.path("response"));
            }
            case "response.failed", "response.incomplete", "error" ->
                    throw new IllegalStateException("模型流式响应未成功完成，事件：" + type);
            default -> {
                // 工具参数必须等完整响应校验完成后，再由 AgentRunner 提交工具执行器。
            }
        }
        return null;
    }
}
