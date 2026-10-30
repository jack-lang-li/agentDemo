package com.example.agent.common.core.context;

/**
 * 调用者类型。
 *
 * 类型与 callerId 共同确定主体含义。
 * 本枚举只表达主体类型，不代表已经接入对应认证方式。
 */
public enum CallerType {

    /** 用户主体，callerId 表示内部用户 ID。 */
    USER,

    /** 应用主体，callerId 表示内部应用 ID。 */
    APPLICATION
}