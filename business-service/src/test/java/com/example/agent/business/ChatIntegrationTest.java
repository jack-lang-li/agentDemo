package com.example.agent.business;

import com.example.agent.business.integration.dify.DifyWorkflowClient;
import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.example.agent.business.integration.dify.DifyCallException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加载真实 Controller、Service、注册表和执行策略，只替换最外部的客户端。
 *
 * <p>这样既能发现 Spring 接口注入或策略注册错误，又不会调用真实模型、Dify 或付费 API。
 * 测试覆盖两个已经与前端联调的接口，特别保护 SSE 的结束事件和 Dify 的 JSON 契约。</p>
 */
@SpringBootTest(classes = BusinessServiceApplication.class, properties = {
        "agent.model.api-key=test-key",
        "agent.model.base-url=http://model.invalid",
        "dify.api-key=test-dify-key",
        "dify.base-url=http://dify.invalid/v1",
        "app.security.dev.alice-password=Alice-test-123",
        "app.security.dev.bob-password=Bob-test-123"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
// 使用真实账号加载器创建系统约定的主体，不 Mock CurrentCallerProvider。
// 密码校验与 Session 生命周期另由 AuthenticationIntegrationTest 覆盖。
@WithUserDetails(value = "alice", userDetailsServiceBeanName = "devUserDetailsService")
class ChatIntegrationTest {

    @MockitoBean
    private ModelClient modelClient;

    @MockitoBean
    private DifyWorkflowClient difyWorkflowClient;

    private final MockMvc mockMvc;

    @Autowired
    ChatIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void shouldStreamLocalAgentAnswerAndOnlyThenSendDone() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("你好，");
            onDelta.accept("我可以帮你查询订单。");
            return new ModelResult("你好，我可以帮你查询订单。", List.of(), List.of());
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult result = runStream("你好");

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(response -> assertThat(response.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                        .startsWith("event: status\ndata: {\"message\":\"请求已接收\"}\n\n")
                        .contains("event: status\ndata: {\"message\":\"正在请求模型，第 1 轮\"}\n\n",
                                "event: delta\ndata: {\"text\":\"你好，\"}\n\n",
                                "event: delta\ndata: {\"text\":\"我可以帮你查询订单。\"}\n\n")
                        .endsWith("event: done\ndata: {}\n\n")
                        .doesNotContain("event: error"));
        verify(modelClient).generateStream(argThat(input -> input.message().equals("你好")
                && input.tools().stream().map(tool -> tool.getName()).toList()
                .equals(List.of("query_order", "query_logistics"))), any());
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldKeepPartialTextAndSendErrorWithoutDoneWhenAgentFails() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(1);
            onDelta.accept("已经生成的部分正文");
            throw new IllegalStateException("模拟模型执行失败");
        }).when(modelClient).generateStream(any(ModelInput.class), any());

        MvcResult result = runStream("你好");

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(response -> assertThat(response.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                        .contains("event: delta\ndata: {\"text\":\"已经生成的部分正文\"}\n\n")
                        .endsWith("event: error\ndata: {\"message\":\"本次生成失败，请稍后重试\"}\n\n")
                        .doesNotContain("event: done", "模拟模型执行失败"));
        verifyNoInteractions(difyWorkflowClient);
    }

    @Test
    void shouldReturnDifyJsonAnswerWithServerSideUserId() throws Exception {
        when(difyWorkflowClient.run("现在北京时间几点？", "demo-user-1"))
                .thenReturn("现在是北京时间 12:00。");

        mockMvc.perform(post("/api/chat/dify")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"message":"现在北京时间几点？"}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.answer").value("现在是北京时间 12:00。"));
        verify(difyWorkflowClient).run("现在北京时间几点？", "demo-user-1");
        verifyNoInteractions(modelClient);
    }

    @Test
    void shouldPreserveDifyBadGatewayErrorContract() throws Exception {
        when(difyWorkflowClient.run("你好", "demo-user-1"))
                .thenThrow(new DifyCallException("模拟工作流失败"));

        mockMvc.perform(post("/api/chat/dify")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("智能体服务暂时不可用，请稍后重试"));
        verifyNoInteractions(modelClient);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void shouldRejectMissingOrBlankMessageBeforeCallingExecutors(String path, String body) throws Exception {
        mockMvc.perform(post(path)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("message 不能为空"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/chat", "/api/chat/dify"})
    void shouldRejectMalformedJson(String path) throws Exception {
        mockMvc.perform(post(path)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请求体必须是合法的 JSON 对象"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/chat", "/api/chat/dify"})
    void shouldRejectMissingBody(String path) throws Exception {
        mockMvc.perform(post(path)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请求体必须是合法的 JSON 对象"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    @Test
    @WithAnonymousUser
    void shouldExposeHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verifyNoInteractions(modelClient, difyWorkflowClient);
    }

    private MvcResult runStream(String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/chat/chat")
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        // 等异步写入结束后再派发，避免在正文尚未写完时读取响应造成偶发失败。
        result.getAsyncResult(5_000L);
        return result;
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of("/api/chat/chat", "/api/chat/dify")
                .flatMap(path -> Stream.of("{}", "{\"message\":null}", "{\"message\":\"\"}",
                                "{\"message\":\"   \"}")
                        .map(body -> Arguments.of(path, body)));
    }
}
