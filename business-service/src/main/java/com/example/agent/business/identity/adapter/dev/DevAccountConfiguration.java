package com.example.agent.business.identity.adapter.dev;

import com.example.agent.business.identity.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * 开发账号数据源。
 *
 * 账号暂时存放内存，但认证流程是真实的。
 * 后续通过数据库版 UserDetailsService 替换此配置。
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
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
                        passwordEncoder.encode(alicePassword)
                ),
                "bob", new Account(
                        "demo-tenant-1",
                        "demo-user-2",
                        passwordEncoder.encode(bobPassword)
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
                    account.passwordHash()
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
            String passwordHash) {
    }
}