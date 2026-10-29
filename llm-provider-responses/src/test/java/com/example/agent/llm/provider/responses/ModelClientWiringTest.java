package com.example.agent.llm.provider.responses;

import com.example.agent.llm.provider.responses.config.ResponsesProviderConfiguration;

import com.example.agent.llm.api.ModelClient;

import com.example.agent.llm.provider.responses.ResponsesModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/** 单独验证真实模型适配器的依赖注入，避免 HTTP 集成测试的模型 Mock 隐藏接线错误。 */
class ModelClientWiringTest {

    @Test
    void shouldWireActualAdapterWithDistinctBlockingAndStreamingClients() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(ResponsesProviderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context.getBean(ModelClient.class)).isInstanceOf(ResponsesModelClient.class);
                    assertThat(context.getBean("modelRestClient"))
                            .isNotSameAs(context.getBean("modelStreamRestClient"));
                });
    }
}
