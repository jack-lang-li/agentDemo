package com.example.agent.business.chat.execution;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天执行策略注册表，统一处理实现收集、重复检查和能力查找。
 *
 * <p>Spring 在启动时通过构造器注入所有 ChatExecutor Bean，包括实现了
 * StreamingChatExecutor 的 Bean。注册完成后映射只读，请求之间共享映射，
 * 每次任务的可变数据仍由对应执行方法单独创建。</p>
 */
@Component
public class ChatExecutorRegistry {

    private final Map<String, ChatExecutor> executors;

    public ChatExecutorRegistry(List<ChatExecutor> chatExecutors) {
        if (chatExecutors.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个聊天执行器");
        }

        Map<String, ChatExecutor> registered = new LinkedHashMap<>();
        for (ChatExecutor executor : chatExecutors) {
            String code = executor.code();
            if (code == null || code.isBlank()) {
                throw new IllegalStateException("聊天执行器编码不能为空");
            }

            // 重复编码属于配置错误，启动时立即暴露，不能默默覆盖为最后一个实现。
            if (registered.putIfAbsent(code, executor) != null) {
                throw new IllegalStateException("聊天执行器编码重复：" + code);
            }
        }
        this.executors = Collections.unmodifiableMap(registered);
    }

    /**
     * 按业务编码取得执行器，不依赖 Bean 名称、注册顺序或默认优先级。
     *
     * @param code 调用方明确选择的执行方式
     * @return 对应执行器
     * @throws IllegalArgumentException 编码未注册
     */
    public ChatExecutor getRequired(String code) {
        ChatExecutor executor = executors.get(code);
        if (executor == null) {
            throw new IllegalArgumentException("不支持的聊天执行方式：" + code);
        }
        return executor;
    }

    /**
     * 获取执行器并确认流式能力，避免任务已经执行后才发现不能增量输出。
     *
     * @param code 调用方明确选择的执行方式
     * @return 支持流式执行的策略
     * @throws IllegalArgumentException 编码未注册，或对应实现仅支持阻塞调用
     */
    public StreamingChatExecutor getStreamingRequired(String code) {
        ChatExecutor executor = getRequired(code);
        if (executor instanceof StreamingChatExecutor streamingExecutor) {
            return streamingExecutor;
        }
        throw new IllegalArgumentException("聊天执行方式不支持流式输出：" + code);
    }
}
