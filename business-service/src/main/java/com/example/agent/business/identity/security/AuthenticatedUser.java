package com.example.agent.business.identity.security;

import com.example.agent.common.core.context.CallerContext;
import com.example.agent.common.core.context.CallerType;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
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

    /** 仅在密码校验期间需要，认证成功后由框架清理。 */
    private String password;

    public AuthenticatedUser(
            String tenantId,
            String userId,
            String username,
            String passwordHash) {

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

        this.tenantId = checked.tenantId();
        this.userId = checked.callerId();
        this.username = username;
        this.password = passwordHash;
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
        // 本节只判断是否登录，具体权限下一节加入。
        return List.of();
    }

    @Override
    public void eraseCredentials() {
        password = null;
    }
}