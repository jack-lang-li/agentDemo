-- 一个订单一个归属用户、最多一个运单。
-- 业务数据按租户隔离；运单号在同一租户内唯一。

CREATE TABLE biz_order
(
    tenant_id VARCHAR(64) NOT NULL COMMENT '所属客户或租户ID',

    order_id VARCHAR(64) NOT NULL COMMENT '租户内订单编号',

    owner_user_id VARCHAR(64) NOT NULL
        COMMENT '订单归属的内部用户ID',

    shipping_status VARCHAR(32) NOT NULL DEFAULT 'NOT_SHIPPED'
        COMMENT 'NOT_SHIPPED未发货，SHIPPED已发货',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定UTC',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '修改时间，约定UTC',

    PRIMARY KEY (tenant_id, order_id),

    KEY idx_biz_order_owner (tenant_id, owner_user_id),

    CONSTRAINT fk_biz_order_owner
        FOREIGN KEY (tenant_id, owner_user_id)
            REFERENCES iam_user (tenant_id, user_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,

    CONSTRAINT ck_biz_order_status
        CHECK (shipping_status IN ('NOT_SHIPPED', 'SHIPPED')),

    CONSTRAINT ck_biz_order_id
        CHECK (
            CHAR_LENGTH(TRIM(order_id)) > 0
                AND CHAR_LENGTH(order_id) = CHAR_LENGTH(TRIM(order_id))
            )
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '具有明确用户归属的订单';


CREATE TABLE biz_order_shipment
(
    tenant_id VARCHAR(64) NOT NULL COMMENT '所属客户或租户ID',

    tracking_no VARCHAR(128) NOT NULL
        COMMENT '租户内唯一的运单号',

    order_id VARCHAR(64) NOT NULL COMMENT '所属订单编号',

    carrier VARCHAR(64) NOT NULL COMMENT '物流公司',

    current_location VARCHAR(200) NOT NULL
        COMMENT '当前物流位置快照',

    latest_event VARCHAR(500) NOT NULL
        COMMENT '最新物流记录快照',

    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        COMMENT '创建时间，约定UTC',

    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3)
        COMMENT '修改时间，约定UTC',

    PRIMARY KEY (tenant_id, tracking_no),

    UNIQUE KEY uk_biz_shipment_order (tenant_id, order_id),

    CONSTRAINT fk_biz_shipment_order
        FOREIGN KEY (tenant_id, order_id)
            REFERENCES biz_order (tenant_id, order_id)
            ON DELETE RESTRICT
            ON UPDATE RESTRICT,

    CONSTRAINT ck_biz_shipment_tracking
        CHECK (
            CHAR_LENGTH(TRIM(tracking_no)) > 0
                AND CHAR_LENGTH(tracking_no) = CHAR_LENGTH(TRIM(tracking_no))
            )
)
    ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_0900_as_cs
COMMENT = '订单运单及最新物流快照';