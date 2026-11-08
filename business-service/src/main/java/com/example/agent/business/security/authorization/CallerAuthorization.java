package com.example.agent.business.security.authorization;

import com.example.agent.common.core.context.CallerContext;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

/**
 * 一次业务调用使用的身份及权限快照。
 *
 * 仅由可信服务端入口构造；构造校验本身不证明身份真实。
 * 集合在创建时复制，适合显式传入 SSE 等异步执行。
 * 不包含密码、Session ID，也不负责实时刷新或撤销权限。
 */
public record CallerAuthorization(
        CallerContext caller,
        Set<String> permissionCodes) {

    public CallerAuthorization {
        if (caller == null) {
            throw new IllegalArgumentException("caller 不能为空");
        }

        if (permissionCodes == null) {
            throw new IllegalArgumentException(
                    "permissionCodes 不能为 null"
            );
        }

        for (String code : permissionCodes) {
            if (code == null
                    || code.isBlank()
                    || !code.equals(code.strip())) {

                throw new IllegalArgumentException(
                        "permissionCode 不能为空或包含首尾空白"
                );
            }
        }

        // 创建独立且不可修改的权限集合。
        permissionCodes = Set.copyOf(permissionCodes);
    }

    /** 按完整权限码精确匹配，不隐含角色前缀或通配符。 */
    public boolean hasPermission(String permissionCode) {
        return permissionCode != null
                && permissionCodes.contains(permissionCode);
    }

    public void requirePermission(String permissionCode) {
        if (!hasPermission(permissionCode)) {
            throw new AccessDeniedException(
                    "缺少业务权限：" + permissionCode
            );
        }
    }
}