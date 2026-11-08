package com.example.agent.business.identity.adapter.persistence.model;

import lombok.Getter;
import lombok.Setter;

/**
 * 本地账号认证查询结果。
 *
 * 这是多表联合查询的投影，包含密码哈希，
 * 仅供认证适配器使用，不作为 Controller 返回类型。
 */
@Getter
@Setter
public class LocalAccountRow {

    private String tenantId;

    private String userId;

    private String username;

    private String passwordHash;

    /** iam_user.enabled。 */
    private Integer userEnabled;

    /** iam_local_account.enabled。 */
    private Integer accountEnabled;
}