package com.example.agent.tool;

import com.example.agent.client.enums.ToolErrorCode;
import com.example.agent.client.model.ToolCall;
import com.example.agent.client.model.ToolExecutionResult;
import com.example.agent.client.model.ToolResult;
import com.example.agent.exception.InvalidToolArgumentsException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 公共工具执行能力：校验模型调用、限定本次可用工具、执行并序列化结果。
 *
 * <p>此类不包含订单、物流等业务判断，也不拼装模型平台的历史消息字段。
 * 先检查本轮所有调用的名称和参数 JSON 结构，再执行任何工具，减少后一个请求明显无效时
 * 前一个工具已经发生业务操作的情况。业务执行自身的失败不具备跨工具事务回滚能力。</p>
 */
@Component
@RequiredArgsConstructor
public class ToolCallExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolCallExecutor.class);

    private final ObjectMapper objectMapper;

    /**
     * 按模型给出的顺序执行本轮工具，每个结果保留原 callId 供下一轮关联。
     *
     * @param calls 本轮完整模型响应中提取的调用请求
     * @param registry 本次业务显式开放的工具集合
     * @return 业务结果的 JSON 文本列表，顺序与请求一致
     */
    public List<ToolResult> execute(List<ToolCall> calls, ToolRegistry registry) {
        List<PreparedCall> prepared = new ArrayList<>();
        Set<String> callIds = new HashSet<>();
        for (ToolCall call : calls) {
            if (call.callId() == null || call.callId().isBlank()) {
                throw new IllegalArgumentException("工具调用缺少call_id");
            }
            if (!callIds.add(call.callId())) {
                throw new IllegalArgumentException("本轮工具调用的call_id重复：" + call.callId());
            }
            AgentTool tool = registry.getRequired(call.name());
            if (call.arguments() == null) {
                throw new IllegalArgumentException("工具调用缺少参数");
            }

            JsonNode arguments;
            try {
                arguments = objectMapper.readTree(call.arguments());
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("工具参数不是合法JSON", exception);
            }
            if (arguments == null || !arguments.isObject()) {
                throw new IllegalArgumentException("工具参数必须是JSON对象");
            }
            prepared.add(new PreparedCall(call, tool, arguments));
        }

        List<ToolResult> results = new ArrayList<>();
        for (PreparedCall item : prepared) {

            ToolExecutionResult executionResult;

            try {
                // 1. 工具正常返回：包装业务数据。
                Object businessData = item.tool().execute(item.arguments());
                executionResult = ToolExecutionResult.success(businessData);

            } catch (InvalidToolArgumentsException exception) {

                // 2. 只把明确的输入错误转换成模型可读取的结果。
                // 此处不重试，后续由模型根据错误信息决定是否重新调用。
                executionResult = ToolExecutionResult.failure(
                        ToolErrorCode.INVALID_ARGUMENTS,
                        exception.getMessage()
                );

                logger.warn(
                        "工具参数校验失败：name={}, callId={}, message={}",
                        item.call().name(),
                        item.call().callId(),
                        exception.getMessage()
                );
            }

            // 3. 序列化整个执行结果，而不再只序列化业务数据。
            String output;
            try {
                output = objectMapper.writeValueAsString(executionResult);
            } catch (JsonProcessingException exception) {
                // 序列化失败属于程序处理错误，本节继续向上传播。
                throw new IllegalStateException(
                        "工具结果序列化失败", exception
                );
            }

            // 4. 保留原callId。
            // AgentRunner和协议适配器继续使用现有ToolResult完成回传。
            results.add(new ToolResult(item.call().callId(), output));

            logger.info(
                    "工具执行完成：name={}, callId={}, status={}",
                    item.call().name(),
                    item.call().callId(),
                    executionResult.status().getValue()
            );
        }
        return List.copyOf(results);
    }

    private record PreparedCall(ToolCall call, AgentTool tool, JsonNode arguments) {
    }
}
