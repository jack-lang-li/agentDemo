package com.example.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.model")
public class ModelProperties {

    /**
     * 当前模型服务提供商。
     * 当前阶段只使用 openai。
     */
    private String provider;

    /**
     * 模型服务基础地址。
     */
    private String baseUrl;

    /**
     * 调用模型服务使用的 API Key。
     */
    private String apiKey;

    /**
     * 当前使用的模型名称。
     */
    private String model;
}