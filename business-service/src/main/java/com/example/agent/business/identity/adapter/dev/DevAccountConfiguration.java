package com.example.agent.business.identity.adapter.dev;

import com.example.agent.business.identity.security.AuthenticatedUser;
import com.example.agent.business.security.permission.BusinessPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Set;

/**
 * 可选的内存开发账号数据源。
 *
 * 仅在 dev 和 dev-memory 同时激活时生效，
 * 用于本地演示及自动化回归测试。
 * 正常数据库模式使用 DatabaseUserDetailsService。
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev & dev-memory")
public class DevAccountConfiguration {

    @Bean
    public UserDetailsService devUserDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.dev.alice-password}")
            String alicePassword,
            @Value("${app.security.dev.bob-password}")
            String bobPassword) {

        requirePassword(alicePassword);
        requirePassword(bobPassword);

        Map<String, Account> accounts = Map.of(
                "alice", new Account(
                        "demo-tenant-1",
                        "demo-user-1",
                        passwordEncoder.encode(alicePassword),
                        // alice 可以使用两个聊天入口。
                        Set.of(
                                BusinessPermissions.CHAT_AGENT_INVOKE,
                                BusinessPermissions.CHAT_WORKFLOW_INVOKE
                        )
                ),
                "bob", new Account(
                        "demo-tenant-1",
                        "demo-user-2",
                        passwordEncoder.encode(bobPassword),
                        // bob 只可以使用工作流聊天入口。
                        Set.of(
                                BusinessPermissions.CHAT_WORKFLOW_INVOKE
                        )
                )
        );

        return username -> {
            Account account = accounts.get(username);

            if (account == null) {
                throw new UsernameNotFoundException("账号不存在");
            }

            // 每次加载创建新的认证主体。
            // 避免认证成功清理密码后，污染账号数据源。
            return new AuthenticatedUser(
                    account.tenantId(),
                    account.userId(),
                    username,
                    account.passwordHash(),
                    account.permissionCodes(),
                    true
            );
        };
    }

    private static void requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "开发账号密码不能为空"
            );
        }
    }

    private record Account(
            String tenantId,
            String userId,
            String passwordHash,
            Set<String> permissionCodes) {
    }
}