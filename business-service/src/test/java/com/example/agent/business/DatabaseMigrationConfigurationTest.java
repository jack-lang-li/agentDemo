package com.example.agent.business;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查生产配置能定位到当前身份与授权迁移资源。
 * 回归此前 YAML 层级和资源路径错误；数据库 SQL 执行仍需真实 MySQL 验证。
 */
class DatabaseMigrationConfigurationTest {

    @Test
    void shouldDiscoverIdentityAndAuthorizationMigrationsAtConfiguredFlywayLocation() throws Exception {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties configuration = yaml.getObject();
        assertThat(configuration).isNotNull();

        String location = configuration.getProperty("spring.flyway.locations");
        assertThat(location)
                .as("Flyway 扫描目录必须位于 spring.flyway.locations")
                .isNotBlank()
                .startsWith("classpath:");

        var migrations = new PathMatchingResourcePatternResolver()
                .getResources(location + "/V*__*.sql");
        assertThat(Arrays.stream(migrations).map(resource -> resource.getFilename()).toList())
                .as("配置目录内应存在正确命名的 V1 至 V5 迁移")
                .contains(
                        "V1__create_iam_user.sql",
                        "V2__create_iam_local_account.sql",
                        "V3__create_iam_rbac.sql",
                        "V4__seed_chat_permissions.sql",
                        "V5__seed_tool_permissions.sql");
    }
}
