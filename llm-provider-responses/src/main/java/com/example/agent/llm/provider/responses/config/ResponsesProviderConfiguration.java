package com.example.agent.llm.provider.responses.config;

import com.example.agent.llm.provider.responses.ResponsesModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelCodec;
import com.example.agent.llm.provider.responses.ResponsesStreamReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Responses 适配库唯一的 Spring 装配入口。
 * 使用方显式导入，适配库不通过全局组件扫描向其他服务自动注入 Bean。
 * HTTP 客户端配置保留同步缓冲与流式无缓冲的分离。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProperties.class)
@Import(ModelClientConfig.class)
public class ResponsesProviderConfiguration {

    @Bean
    public ResponsesModelCodec responsesModelCodec(ObjectMapper objectMapper) {
        return new ResponsesModelCodec(objectMapper);
    }

    @Bean
    public ResponsesStreamReader responsesStreamReader(
            ObjectMapper objectMapper, ResponsesModelCodec codec) {
        return new ResponsesStreamReader(objectMapper, codec);
    }

    @Bean
    public ResponsesModelClient responsesModelClient(
            ModelProperties properties,
            @Qualifier("modelRestClient") RestClient blocking,
            @Qualifier("modelStreamRestClient") RestClient streaming,
            ResponsesModelCodec codec,
            ResponsesStreamReader reader) {
        return new ResponsesModelClient(properties, blocking, streaming, codec, reader);
    }
}
