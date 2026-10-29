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
     * 当前字段用于标识提供商，修改名称不会自动改变通信协议。
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

    /** 单次模型 HTTP 建连超时，同步和流式共用，单位为毫秒。 */
    private int connectTimeoutMillis = 10_000;

    /** 等待上游数据的读取超时，流式情况下不是整个回答的总耗时限制。 */
    private int readTimeoutMillis = 90_000;
}
