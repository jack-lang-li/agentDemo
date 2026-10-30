package com.example.agent.business.identity.api;


import com.example.agent.common.core.context.CallerContext;

/**
 * 当前业务入口获取调用者的契约。
 *
 * <p>Controller 等入口获取上下文后，将其放入业务命令。
 * Service 和执行器使用命令中的身份，
 * 不需要再次读取当前 HTTP 请求。</p>
 *
 * <p>实现应返回完整上下文。
 * 无法确定调用者时应明确失败，不返回 null，
 * 也不自动回退到默认用户。</p>
 *
 * <p>当前提供开发模拟实现。
 * 后续认证阶段再实现从认证结果提取内部身份。</p>
 */
public interface CurrentCallerProvider {

    /**
     * 获取本次入口关联的调用者上下文。
     *
     * @return 非 null、字段完整的上下文
     */
    CallerContext requireCurrentCaller();
}