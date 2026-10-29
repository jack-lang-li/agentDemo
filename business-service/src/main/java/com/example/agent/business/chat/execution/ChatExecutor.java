package com.example.agent.business.chat.execution;

import com.example.agent.business.chat.application.model.ChatCommand;

/**
 * 聊天任务执行策略的公共契约。
 *
 * <p>不同实现完成相同的业务目标：根据请求生成回答。
 * 具体使用自建 Agent、外部工作流还是其他平台，由实现自行适配。
 * 实现应保持无请求状态，问题、上下文和执行结果应放在方法局部变量中，
 * 以便 Spring 单例 Bean 安全地服务多个并发请求。</p>
 */
public interface ChatExecutor {

    /**
     * 返回稳定且非空的业务编码，不能与其他实现重复。
     *
     * <p>编码用于显式路由，与 Spring Bean 名称无关；重命名实现类不应改变调用规则。
     * 接口允许返回新的编码，因此新增执行方式不必修改注册表或维护一组固定枚举。</p>
     *
     * @return 当前执行方式的唯一编码，例如 local-agent
     */
    String code();

    /**
     * 执行任务并等待完整回答。
     *
     * @param command 本次执行参数，包含问题和服务端用户标识
     * @return 完整回答；失败应抛出异常，不能把错误消息伪装成成功答案
     */
    String execute(ChatCommand command);
}
