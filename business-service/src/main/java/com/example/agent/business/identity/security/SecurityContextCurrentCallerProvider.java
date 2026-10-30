package com.example.agent.business.identity.security;

import com.example.agent.business.identity.api.CurrentCallerProvider;
import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.common.core.context.CallerContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 认证结果取得内部调用者。
 *
 * 只在服务端入口读取认证上下文。
 * 下游业务继续使用 ChatCommand 显式传递的 CallerContext。
 */
@Component
public class SecurityContextCurrentCallerProvider
        implements CurrentCallerProvider {

    @Override
    public CallerContext requireCurrentCaller() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {

            throw new AuthenticationCredentialsNotFoundException(
                    "当前请求尚未认证"
            );
        }

        if (!(authentication.getPrincipal()
                instanceof AuthenticatedUser user)) {

            // 已认证却不是系统约定的主体，属于接入或装配问题。
            // 明确失败，不回退到默认用户。
            throw new IllegalStateException(
                    "不支持的认证主体类型"
            );
        }

        return user.callerContext();
    }
}