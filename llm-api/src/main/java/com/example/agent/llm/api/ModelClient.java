package com.example.agent.llm.api;

import com.example.agent.llm.api.model.ModelInput;
import com.example.agent.llm.api.model.ModelResult;

import java.util.function.Consumer;

/**
 * 供各业务直接复用的单次模型调用接口，不执行 Java 工具，也不进行 Agent 自动循环。
 *
 * <p>业务只需要正文时调用 chat/chatStream；需要处理工具请求或续接上下文时调用
 * generate/generateStream。底层实现负责平台配置、HTTP 请求、协议编解码与流式解析。
 * 当前提供 Responses 协议实现，后续其他协议可在此接口后新增适配器。</p>
 */
public interface ModelClient {

    /**
     * 执行一次模型请求，返回完整结果，包括模型可能提出的工具调用。
     * tools 只来自本次 input，不从全局工具容器补充，方法本身不会执行工具。
     */
    ModelResult generate(ModelInput input);

    /**
     * 执行一次真实流式模型请求，逐段回调正文，收到完整完成响应后才返回结果。
     * 连接提前结束、上游失败或回调抛出异常均属于失败，不将部分正文作为成功结果返回。
     */
    ModelResult generateStream(ModelInput input, Consumer<String> onDelta);

    /** 普通业务最简单的用法：不携带工具，只等待一次完整回答。 */
    default String chat(String message) {
        return chat(null, message);
    }

    /** 使用业务自己的提示词与问题进行一次生成，不引入 Agent 或业务工具。 */
    default String chat(String systemPrompt, String message) {
        return generate(ModelInput.text(systemPrompt, message)).requireText();
    }

    /** 流式生成正文，方法正常结束时同时返回本次完整正文，方便业务后续保存或处理。 */
    default String chatStream(String message, Consumer<String> onDelta) {
        return chatStream(null, message, onDelta);
    }

    /** 带业务提示词的单次流式生成；回调为正文增量，返回值来自完整完成响应。 */
    default String chatStream(String systemPrompt, String message, Consumer<String> onDelta) {
        return generateStream(ModelInput.text(systemPrompt, message), onDelta).requireText();
    }
}
