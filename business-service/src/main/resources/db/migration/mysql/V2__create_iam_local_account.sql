-- 本地密码登录账号。
--
-- 一个内部用户最多关联一个本地账号，因此 user_id 同时作为主键。
-- 后续 SSO 身份通过其他身份映射关联 iam_user。

CREATE TABLE iam_local_account
(
    user_id VARCHAR(64) NOT NULL
        COMMENT '关联的内部用户ID',

    tenant_id VARCHAR(64) NOT NULL
        COMMENT '所属客户或租户ID',

    username VARCHAR(255) NOT NULL
        COMMENT '本地登录名，同一租户内唯一，区分大小写',

    password_hash VARCHAR(255) NOT NULL
        COMMENT 'PasswordEncoder 生成的密码哈希',

    enabled TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '本地账号状态：1启用，0停用',

    password_changed_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '密码最后修改时间，修改密码时由应用同步更新，约定UTC',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定UTC',

    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '最后更新时间，约定UTC',

    PRIMARY KEY (user_id),

    UNIQUE KEY uk_iam_local_account_tenant_username
        (tenant_id, username),

    KEY idx_iam_local_account_tenant_user
        (tenant_id, user_id),

    -- 同时关联租户和用户，防止账号挂到其他租户的用户上。
    CONSTRAINT fk_iam_local_account_user
        FOREIGN KEY (tenant_id, user_id)
            REFERENCES iam_user (tenant_id, user_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,

    CONSTRAINT ck_iam_local_account_enabled
        CHECK (enabled IN (0, 1)),

    -- 登录名不能为空或包含首尾普通空格。
    CONSTRAINT ck_iam_local_account_username
        CHECK (
            CHAR_LENGTH(TRIM(username)) > 0
                AND CHAR_LENGTH(username) = CHAR_LENGTH(TRIM(username))
            ),

    CONSTRAINT ck_iam_local_account_password_hash
        CHECK (CHAR_LENGTH(password_hash) > 0)
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '本地密码登录账号';