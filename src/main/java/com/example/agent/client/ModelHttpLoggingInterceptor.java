package com.example.agent.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 记录模型 API 的原始 JSON；需配合缓冲请求工厂，避免读取日志后响应体被耗尽。
 */
public class ModelHttpLoggingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ModelHttpLoggingInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        long started = System.nanoTime();
        // 只打印正文，不打印包含 API Key 的 Authorization 请求头。
        logger.info("模型请求：method={}, path={}, body={}", request.getMethod(),
                request.getURI().getPath(), new String(body, StandardCharsets.UTF_8));
        ClientHttpResponse response = execution.execute(request, body);
        try {
            if (logger.isInfoEnabled()) {
                logger.info("模型响应：status={}, durationMs={}, body={}", response.getStatusCode().value(),
                        (System.nanoTime() - started) / 1_000_000,
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8));
            }
            return response;
        } catch (IOException | RuntimeException exception) {
            response.close();
            throw exception;
        }
    }
}
