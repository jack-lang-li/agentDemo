package com.example.agent.testsupport;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 开发数据的密码哈希生成工具，通过 IDEA 的 main 运行按钮手动使用。
 *
 * 在本工具自己的运行配置中设置 PASSWORD_TO_HASH。
 * 仅输出哈希，不启动 Spring、不连接数据库、不输出原始密码。
 * 编码方式与 BusinessSecurityConfiguration 当前的 BCrypt 配置一致。
 */
public final class PasswordHashGenerator {

    private PasswordHashGenerator() {
    }

    public static void main(String[] args) {
        String password = System.getenv("PASSWORD_TO_HASH");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(
                    "请在此工具的运行配置中设置 PASSWORD_TO_HASH");
        }

        System.out.println(new BCryptPasswordEncoder().encode(password));
    }
}
