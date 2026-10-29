package com.example.agent.business.config;

import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.DefaultAgentRunner;
import com.example.agent.agent.tool.ToolCallExecutor;
import com.example.agent.llm.api.ModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 由业务启动服务装配通用 Agent，核心库不依赖 Spring 组件扫描。 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

    @Bean
    public ToolCallExecutor toolCallExecutor(ObjectMapper objectMapper) {
        return new ToolCallExecutor(objectMapper);
    }

    @Bean
    public AgentRunner agentRunner(ModelClient modelClient, ToolCallExecutor executor) {
        return new DefaultAgentRunner(modelClient, executor);
    }
}
