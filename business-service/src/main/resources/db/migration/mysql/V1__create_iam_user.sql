-- 内部用户主体表。
--
-- user_id 是本系统的稳定内部用户 ID。
-- 后续本地账号、SSO 身份、员工资料都关联这个 ID。
--
-- 用户名和密码将在后续本地登录凭证表中保存，
-- 使内部用户能够关联不同的登录方式。

CREATE TABLE iam_user
(
    user_id VARCHAR(64) NOT NULL
        COMMENT '本系统内部用户ID，由应用生成，创建后保持不变',

    tenant_id VARCHAR(64) NOT NULL
        COMMENT '所属客户或租户的内部ID',

    display_name VARCHAR(100) NOT NULL
        COMMENT '用户展示名称',

    enabled TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '用户状态：1启用，0停用',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定使用UTC',

    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '最后更新时间，约定使用UTC',

    PRIMARY KEY (user_id),

    -- 为后续授权关联表的租户与用户复合外键提供约束。
    UNIQUE KEY uk_iam_user_tenant_user (tenant_id, user_id),

    CONSTRAINT ck_iam_user_enabled
        CHECK (enabled IN (0, 1))
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '内部用户主体';