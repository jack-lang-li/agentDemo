package com.example.agent.business;

import com.example.agent.agent.AgentRunner;
import com.example.agent.agent.DefaultAgentRunner;
import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.provider.responses.ResponsesModelClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/** 保护真实装配，不使用 ModelClient Mock，避免业务集成测试掩盖缺失 Bean。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "dify.api-key=test-dify-key",
        "dify.base-url=http://dify.invalid/v1",
        "logging.file.name=target/test-logs/business-service.log"
})
class BusinessServiceWiringTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate http;

    @Test
    void shouldAssembleLocalModelAgentAndDifyWithoutScanningOtherServices() {
        assertThat(context.getBeansOfType(ModelClient.class)).hasSize(1);
        assertThat(context.getBean(ModelClient.class)).isInstanceOf(ResponsesModelClient.class);
        assertThat(context.getBeansOfType(AgentRunner.class)).hasSize(1);
        assertThat(context.getBean(AgentRunner.class)).isInstanceOf(DefaultAgentRunner.class);
        assertThat(context.getBeansOfType(DifyWorkflowClient.class)).hasSize(1);
    }

    @Test
    void shouldServeHealthThroughRealHttpServer() {
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
