package com.example.agent.llm.service;

import com.example.agent.llm.provider.responses.config.ResponsesProviderConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 模型服务独立启动入口，显式装配模型能力。
 * 当前只完成工程拆分与健康检查，尚未开放模型生成 HTTP 接口。
 */
@SpringBootApplication
@Import(ResponsesProviderConfiguration.class)
public class LlmServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmServiceApplication.class, args);
    }
}
