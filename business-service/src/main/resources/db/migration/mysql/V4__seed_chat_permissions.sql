-- 初始化应用支持的聊天权限目录。
-- 权限码必须与 BusinessPermissions 中的常量保持一致。

INSERT INTO iam_permission
(
    permission_code,
    permission_name,
    description
)
VALUES
    (
        'chat:agent:invoke',
        '使用 Agent 聊天',
        '允许调用 Agent 聊天入口，工具权限后续单独检查'
    ),
    (
        'chat:workflow:invoke',
        '使用工作流聊天',
        '允许调用工作流聊天入口，当前实现为 Dify'
    );