-- 登记应用支持的工具权限。
-- 此迁移只创建权限目录，不自动向任何角色授予权限。

INSERT INTO iam_permission
(
    permission_code,
    permission_name,
    description
)
VALUES
    (
        'order:query',
        '查询订单',
        '允许使用订单查询能力，具体数据范围另行校验'
    ),
    (
        'logistics:query',
        '查询物流',
        '允许使用物流查询能力，具体数据范围另行校验'
    );