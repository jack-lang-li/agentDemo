package com.example.agent.llm.service;

import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实独立启动配置及本机 HTTP，不访问任何模型或业务平台。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "logging.file.name=target/test-logs/llm-service.log"
})
class LlmServiceWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Test
    void shouldAssembleModelWithoutBusinessOrDifyBeans() {
        assertThat(context.getBeansOfType(ModelClient.class)).hasSize(1);
        assertThat(context.getBean(ModelClient.class)).isInstanceOf(ResponsesModelClient.class);
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
                .map(context::getType)
                .filter(type -> type != null)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("com.example.agent.business."));
    }

    @Test
    void shouldServeHealthAndNotExposeBusinessChatEndpoints() {
        var health = http.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(http.postForEntity("/api/chat/chat", java.util.Map.of("message", "你好"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
