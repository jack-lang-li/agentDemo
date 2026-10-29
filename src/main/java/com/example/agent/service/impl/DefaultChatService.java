package com.example.agent.service.impl;

import com.example.agent.dto.ChatResponse;
import com.example.agent.executor.ChatExecutorRegistry;
import com.example.agent.service.ChatService;
import com.example.agent.service.model.ChatCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 聊天应用服务的默认实现，集中处理策略路由和业务响应组装。
 *
 * <p>此类只依赖注册表中的抽象契约，不导入任何模型或平台客户端。
 * 后续增加满足相同契约的执行器时，无需在此处增加厂商分支。
 * Spring 当前只有这个 ChatService 实现，多个执行策略由注册表统一管理。</p>
 */
@Service
@RequiredArgsConstructor
public class DefaultChatService implements ChatService {

    private final ChatExecutorRegistry executorRegistry;

    @Override
    public ChatResponse chat(String executorCode, ChatCommand command) {
        Objects.requireNonNull(command, "聊天执行参数不能为空");
        String answer = executorRegistry.getRequired(executorCode).execute(command);
        return new ChatResponse(answer);
    }

    @Override
    public void chatStream(
            String executorCode,
            ChatCommand command,
            Consumer<String> onDelta,
            Consumer<String> onStatus) {

        Objects.requireNonNull(command, "聊天执行参数不能为空");
        Objects.requireNonNull(onDelta, "正文回调不能为空");
        Objects.requireNonNull(onStatus, "状态回调不能为空");

        // 查找与能力检查发生在执行前；不支持流式的策略不会被降级成阻塞调用。
        // 正常返回、执行异常和回调异常的语义与具体执行器保持一致。
        executorRegistry.getStreamingRequired(executorCode)
                .executeStream(command, onDelta, onStatus);
    }
}
