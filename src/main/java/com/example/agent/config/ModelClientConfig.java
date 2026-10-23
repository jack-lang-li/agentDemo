package com.example.agent.config;

import com.example.agent.client.ModelHttpLoggingInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ModelClientConfig {

    @Bean
    public RestClient modelRestClient(RestClient.Builder builder) {
        return builder
                .requestFactory(new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory()))
                .requestInterceptor(new ModelHttpLoggingInterceptor())
                .build();
    }
}
