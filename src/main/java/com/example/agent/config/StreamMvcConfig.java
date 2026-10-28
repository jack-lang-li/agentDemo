package com.example.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StreamMvcConfig implements WebMvcConfigurer {

    @Bean
    public ThreadPoolTaskExecutor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("chat-stream-");

        return executor;
    }

    @Override
    public void configureAsyncSupport(
            AsyncSupportConfigurer configurer) {

        configurer.setTaskExecutor(chatStreamExecutor());

        // 整个异步 HTTP 请求的超时：5 分钟。
        configurer.setDefaultTimeout(300_000L);
    }
}