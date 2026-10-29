package com.example.agent.business.config;

import com.example.agent.llm.provider.responses.config.ResponsesProviderConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 本轮迁移的过渡装配：业务服务仍在本进程调用模型，保持原有业务行为。
 * 接入远程客户端时，应替换此装配并移除业务服务对 Responses 适配库的依赖。
 * 业务服务始终不依赖 llm-service 可执行应用。
 */
@Configuration(proxyBeanMethods = false)
@Import(ResponsesProviderConfiguration.class)
public class BusinessLocalModelConfiguration {
}
