package com.example.agent.llm.provider.responses;

import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.example.agent.llm.provider.responses.config.ModelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * ModelClient 的 Responses 协议实现，每次调用只向模型发送一次 HTTP 请求。
 *
 * <p>当前继续使用已经联调的 /v1/responses 与服务端模型配置。
 * 同步与流式使用分别配置的 RestClient，流式客户端不安装读取完整响应体的日志拦截器。
 * 此类不持有 ToolRegistry，不知道订单或物流业务，也不会自动执行模型提出的工具。</p>
 */
public class ResponsesModelClient implements ModelClient {

    private static final Logger logger = LoggerFactory.getLogger(ResponsesModelClient.class);

    private final ModelProperties properties;
    private final RestClient modelRestClient;
    private final RestClient streamRestClient;
    private final ResponsesModelCodec codec;
    private final ResponsesStreamReader streamReader;

    public ResponsesModelClient(
            ModelProperties properties,
            @Qualifier("modelRestClient") RestClient modelRestClient,
            @Qualifier("modelStreamRestClient") RestClient streamRestClient,
            ResponsesModelCodec codec,
            ResponsesStreamReader streamReader) {
        this.properties = properties;
        this.modelRestClient = modelRestClient;
        this.streamRestClient = streamRestClient;
        this.codec = codec;
        this.streamReader = streamReader;
    }

    @Override
    public ModelResult generate(ModelInput input) {
        Objects.requireNonNull(input, "模型输入不能为空");
        try {
            JsonNode response = modelRestClient.post()
                    .uri(properties.getBaseUrl() + "/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(codec.encode(input, properties.getModel(), false))
                    .retrieve()
                    .body(JsonNode.class);
            return codec.decode(response);
        } catch (RestClientException exception) {
            logger.error("模型调用失败：model={}", properties.getModel(), exception);
            throw exception;
        }
    }

    @Override
    public ModelResult generateStream(ModelInput input, Consumer<String> onDelta) {
        Objects.requireNonNull(input, "模型输入不能为空");
        Objects.requireNonNull(onDelta, "正文回调不能为空");
        try {
            return streamRestClient.post()
                    .uri(properties.getBaseUrl() + "/v1/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(codec.encode(input, properties.getModel(), true))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("模型流式请求失败，HTTP 状态码："
                                    + response.getStatusCode().value());
                        }
                        MediaType contentType = response.getHeaders().getContentType();
                        if (contentType == null || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
                            throw new IllegalStateException("模型未返回 text/event-stream，请检查流式接口支持情况");
                        }
                        // 必须在 exchange 生命周期内消费输入流，返回后 RestClient 会关闭响应。
                        return streamReader.read(response.getBody(), onDelta);
                    });
        } catch (RestClientException exception) {
            logger.error("模型流式调用失败：model={}", properties.getModel(), exception);
            throw exception;
        }
    }
}
