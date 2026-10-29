package com.example.agent.llm.provider.responses.config;

import com.example.agent.llm.provider.responses.ModelHttpLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfig {

    @Bean
    public RestClient modelRestClient(RestClient.Builder builder, ModelProperties properties) {
        return builder
                .requestFactory(new BufferingClientHttpRequestFactory(requestFactory(properties)))
                .requestInterceptor(new ModelHttpLoggingInterceptor())
                .build();
    }

    /**
     * 流式传输独立使用无缓冲、无正文日志拦截器的客户端。
     * 直接创建 Builder，避免同步客户端的缓冲或拦截器配置进入流式链路。
     */
    @Bean
    public RestClient modelStreamRestClient(ModelProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties)).build();
    }

    /** 两种传输统一读取模型连接配置，分别创建工厂，避免请求状态共享。 */
    private SimpleClientHttpRequestFactory requestFactory(ModelProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMillis());
        factory.setReadTimeout(properties.getReadTimeoutMillis());
        return factory;
    }
}
