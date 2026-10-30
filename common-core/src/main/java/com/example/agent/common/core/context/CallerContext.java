package com.example.agent.common.core.context;

/**
 * 一次业务调用关联的调用者上下文。
 *
 * <p>只保存内部身份标识，不保存密码、访问令牌、
 * 完整用户资料或权限集合。</p>
 *
 * <p>使用 record 保持不可变，当前所有字段也都是不可变类型，
 * 可以在同步与异步执行之间显式传递。</p>
 *
 * <p>构造校验只保证字段完整，不证明身份真实。
 * 身份可信性由创建此上下文的服务端入口负责。</p>
 *
 * @param tenantId 客户或租户范围的内部标识
 * @param callerType 调用者类型
 * @param callerId 对应类型的内部主体标识
 */
public record CallerContext(
        String tenantId,
        CallerType callerType,
        String callerId) {

    public CallerContext {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId 不能为空"
            );
        }

        if (callerType == null) {
            throw new IllegalArgumentException(
                    "callerType 不能为空"
            );
        }

        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException(
                    "callerId 不能为空"
            );
        }
    }
}