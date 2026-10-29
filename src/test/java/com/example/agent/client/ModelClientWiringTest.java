package com.example.agent.client;

import com.example.agent.client.responses.ResponsesModelClient;
import com.example.agent.client.responses.ResponsesModelCodec;
import com.example.agent.client.responses.ResponsesStreamReader;
import com.example.agent.config.ModelClientConfig;
import com.example.agent.config.ModelProperties;
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
                .withBean(ModelProperties.class, ModelProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withUserConfiguration(ModelClientConfig.class, ResponsesModelClient.class,
                        ResponsesModelCodec.class, ResponsesStreamReader.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelClient.class);
                    assertThat(context.getBean(ModelClient.class)).isInstanceOf(ResponsesModelClient.class);
                    assertThat(context.getBean("modelRestClient"))
                            .isNotSameAs(context.getBean("modelStreamRestClient"));
                });
    }
}
