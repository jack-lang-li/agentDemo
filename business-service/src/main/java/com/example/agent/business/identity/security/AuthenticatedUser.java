package com.example.agent.business.identity.security;

import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Spring Security 使用的登录主体。
 *
 * 登录名与内部用户 ID 分开保存。
 * 身份字段不可变，密码哈希在认证成功后允许被清理。
 *
 * 不直接返回给前端，避免暴露凭证字段。
 */
public final class AuthenticatedUser
        implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String tenantId;
    private final String userId;
    private final String username;

    /**
     * 本次登录的权限快照。
     *
     * 集合不可变，避免会话中的权限被普通业务代码修改。
     */
    private final List<GrantedAuthority> authorities;

    /** 仅在密码校验期间需要，认证成功后由框架清理。 */
    private String password;

    public AuthenticatedUser(
            String tenantId,
            String userId,
            String username,
            String passwordHash,
            Collection<String> permissionCodes) {

        // 复用内部身份完整性校验。
        CallerContext checked = new CallerContext(
                tenantId,
                CallerType.USER,
                userId
        );

        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username 不能为空");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash 不能为空");
        }
        // 校验集合本身；集合中的每个权限码由 toAuthority 校验。
        if (permissionCodes == null) {
            throw new IllegalArgumentException(
                    "permissionCodes 不能为 null"
            );
        }

        this.tenantId = checked.tenantId();
        this.userId = checked.callerId();
        this.username = username;
        this.password = passwordHash;
        // 将业务权限码转换为 Spring Security 的权限对象。
        // distinct 去重；Java 17 的 toList 返回不可修改的集合。
        this.authorities = permissionCodes.stream()
                .map(AuthenticatedUser::toAuthority)
                .distinct()
                .toList();
    }

    private static GrantedAuthority toAuthority(
            String permissionCode) {

        if (permissionCode == null
                || permissionCode.isBlank()
                || !permissionCode.equals(permissionCode.strip())) {

            throw new IllegalArgumentException(
                    "permissionCode 不能为空或包含首尾空白"
            );
        }

        return new SimpleGrantedAuthority(permissionCode);
    }

    public CallerContext callerContext() {
        return new CallerContext(
                tenantId,
                CallerType.USER,
                userId
        );
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}