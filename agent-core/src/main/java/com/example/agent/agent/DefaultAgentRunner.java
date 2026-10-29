package com.example.agent.agent;

import com.example.agent.llm.api.ModelClient;
import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;
import com.example.agent.llm.api.model.ToolResult;
import com.example.agent.agent.tool.ToolCallExecutor;
import com.example.agent.agent.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 通用 Agent 循环：请求模型、执行当前业务提供的工具、回传结果、取得最终回答。
 *
 * <p>同步与流式共用同一个循环，避免轮数限制、工具选择和上下文处理逐渐出现差异。
 * 每次执行的工具注册表与模型历史都是局部变量，公共组件不会保存某个业务的工具清单。</p>
 */
@RequiredArgsConstructor
public class DefaultAgentRunner implements AgentRunner {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAgentRunner.class);

    private final ModelClient modelClient;
    private final ToolCallExecutor toolCallExecutor;

    @Override
    public String run(AgentRequest request) {
        return execute(request, false, text -> { }, status -> { });
    }

    @Override
    public void runStream(AgentRequest request, Consumer<String> onDelta, Consumer<String> onStatus) {
        Objects.requireNonNull(onDelta, "正文回调不能为空");
        Objects.requireNonNull(onStatus, "状态回调不能为空");
        execute(request, true, onDelta, onStatus);
    }

    private String execute(
            AgentRequest request,
            boolean streaming,
            Consumer<String> onDelta,
            Consumer<String> onStatus) {

        Objects.requireNonNull(request, "Agent 执行参数不能为空");
        ToolRegistry tools = new ToolRegistry(request.tools());
        ModelInput input = ModelInput.text(request.systemPrompt(), request.message())
                .withTools(tools.definitions());

        for (int round = 1; round <= request.maxModelRounds(); round++) {
            logger.info("Agent开始第{}轮模型请求", round);
            onStatus.accept("正在请求模型，第 " + round + " 轮");
            AtomicBoolean receivedText = new AtomicBoolean(false);
            ModelResult response = streaming
                    ? modelClient.generateStream(input, delta -> {
                        if (!delta.isBlank()) {
                            receivedText.set(true);
                        }
                        onDelta.accept(delta);
                    })
                    : modelClient.generate(input);

            if (!response.hasToolCalls()) {
                if (streaming && !receivedText.get()) {
                    throw new IllegalStateException("模型未返回可展示的正文");
                }
                String answer = response.requireText();
                logger.info("Agent在第{}轮完成任务", round);
                return answer;
            }

            // 最后一轮之后无法再回传工具结果，因此保持原行为，不执行该轮工具。
            if (round == request.maxModelRounds()) {
                break;
            }

            onStatus.accept("正在执行工具");
            List<ToolResult> results = toolCallExecutor.execute(response.toolCalls(), tools);
            input = input.appendTurn(response, results);
            onStatus.accept("工具执行完成，正在继续生成回答");
        }
        throw new IllegalStateException("达到模型调用轮数上限，任务仍未完成：" + request.maxModelRounds());
    }
}
