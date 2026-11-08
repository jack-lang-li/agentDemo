-- 角色属于具体客户或租户。
CREATE TABLE iam_role
(
    role_id VARCHAR(64) NOT NULL
        COMMENT '角色内部ID，由应用生成',

    tenant_id VARCHAR(64) NOT NULL
        COMMENT '所属客户或租户ID',

    role_code VARCHAR(100) NOT NULL
        COMMENT '角色编码，同一租户内唯一',

    role_name VARCHAR(100) NOT NULL
        COMMENT '角色展示名称',

    enabled TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '角色状态：1启用，0停用',

    description VARCHAR(500) NULL
        COMMENT '角色说明',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定UTC',

    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '最后更新时间，约定UTC',

    PRIMARY KEY (role_id),

    UNIQUE KEY uk_iam_role_tenant_code
        (tenant_id, role_code),

    -- 为关联表的租户与角色复合外键提供唯一约束。
    UNIQUE KEY uk_iam_role_tenant_role
        (tenant_id, role_id),

    CONSTRAINT ck_iam_role_enabled
        CHECK (enabled IN (0, 1))
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '业务角色';


-- 权限目录定义应用支持哪些操作。
-- 租户通过自己的角色选择这些权限。
CREATE TABLE iam_permission
(
    permission_code VARCHAR(128) NOT NULL
        COMMENT '稳定业务权限码，与服务端授权规则一致',

    permission_name VARCHAR(100) NOT NULL
        COMMENT '权限展示名称',

    description VARCHAR(500) NULL
        COMMENT '权限说明',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定UTC',

    updated_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '最后更新时间，约定UTC',

    PRIMARY KEY (permission_code)
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '业务权限目录';


-- 一个用户可以拥有多个角色，同一角色也可以分配给多个用户。
CREATE TABLE iam_user_role
(
    tenant_id VARCHAR(64) NOT NULL
        COMMENT '所属客户或租户ID',

    user_id VARCHAR(64) NOT NULL
        COMMENT '内部用户ID',

    role_id VARCHAR(64) NOT NULL
        COMMENT '角色ID',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '角色分配时间，约定UTC',

    -- 防止同一用户重复分配同一个角色。
    PRIMARY KEY (tenant_id, user_id, role_id),

    KEY idx_iam_user_role_tenant_role
        (tenant_id, role_id),

    CONSTRAINT fk_iam_user_role_user
        FOREIGN KEY (tenant_id, user_id)
            REFERENCES iam_user (tenant_id, user_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,

    CONSTRAINT fk_iam_user_role_role
        FOREIGN KEY (tenant_id, role_id)
            REFERENCES iam_role (tenant_id, role_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '用户角色关系';


-- 一个角色可以包含多个权限，同一权限也可以属于多个角色。
CREATE TABLE iam_role_permission
(
    tenant_id VARCHAR(64) NOT NULL
        COMMENT '所属客户或租户ID',

    role_id VARCHAR(64) NOT NULL
        COMMENT '角色ID',

    permission_code VARCHAR(128) NOT NULL
        COMMENT '业务权限码',

    created_at DATETIME(3) NOT NULL
        DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '权限授予时间，约定UTC',

    -- 防止同一角色重复分配同一个权限。
    PRIMARY KEY (tenant_id, role_id, permission_code),

    KEY idx_iam_role_permission_code
        (permission_code),

    CONSTRAINT fk_iam_role_permission_role
        FOREIGN KEY (tenant_id, role_id)
            REFERENCES iam_role (tenant_id, role_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,

    CONSTRAINT fk_iam_role_permission_permission
        FOREIGN KEY (permission_code)
            REFERENCES iam_permission (permission_code)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '角色权限关系';