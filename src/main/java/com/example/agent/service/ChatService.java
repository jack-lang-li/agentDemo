package com.example.agent.service;

import com.example.agent.dto.ChatResponse;
import com.example.agent.service.model.ChatCommand;

import java.util.function.Consumer;

/**
 * 对外提供聊天业务能力的应用服务接口。
 *
 * <p>Controller 通过此接口发起任务，不直接依赖模型客户端或 Dify 客户端。
 * 执行方式通过 executorCode 显式选择；增加实现时不必给此接口增加厂商专用方法。
 * HTTP 请求校验、JSON 序列化和 SSE 帧写入仍由 HTTP 层负责。</p>
 */
public interface ChatService {

    /**
     * 选择执行策略，等待完整回答后组装业务响应。
     *
     * @param executorCode 已注册的执行方式编码
     * @param command 本次请求的业务参数
     * @return 包含完整回答的响应
     */
    ChatResponse chat(String executorCode, ChatCommand command);

    /**
     * 选择支持流式输出的策略，并按生成顺序通知调用方。
     *
     * <p>本方法在当前线程中执行，正常返回表示整个任务成功完成；异常直接向上抛出。
     * Controller 据此发送 done 或 error，执行器本身不接触 HTTP 输出流。
     * 回调抛出的异常也必须继续传播，使连接写入失败能够结束当前执行。</p>
     *
     * @param executorCode 已注册且支持流式输出的执行方式编码
     * @param command 本次请求的业务参数
     * @param onDelta 接收正文增量，调用方应按顺序追加
     * @param onStatus 接收执行进度，不应作为回答正文追加
     */
    void chatStream(
            String executorCode,
            ChatCommand command,
            Consumer<String> onDelta,
            Consumer<String> onStatus
    );
}
