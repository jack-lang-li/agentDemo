package com.example.agent.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "dify")
public class DifyProperties {

    @NotBlank(message = "dify.base-url 不能为空")
    private String baseUrl;

    @NotBlank(message = "请配置环境变量 DIFY_API_KEY")
    private String apiKey;

    @Min(value = 1, message = "连接超时必须大于 0")
    private int connectTimeoutMillis = 10_000;

    @Min(value = 1, message = "读取超时必须大于 0")
    private int readTimeoutMillis = 90_000;
}