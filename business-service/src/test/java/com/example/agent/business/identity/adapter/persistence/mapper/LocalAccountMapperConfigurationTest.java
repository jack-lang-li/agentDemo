package com.example.agent.business.identity.adapter.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.example.agent.business.identity.adapter.persistence.model.LocalAccountRow;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用实际 MyBatis 配置解析生产 Mapper XML，验证参数绑定和查询投影。
 * 此测试不连接数据库，不替代 MySQL 对 SQL 与外键的实际执行验证。
 */
class LocalAccountMapperConfigurationTest {

    @ParameterizedTest
    @CsvSource({"findLoginAccount,username", "findPermissionCodes,userId"})
    void shouldBindTenantAndLookupValueAsParameters(String method, String lookupParameter) throws Exception {
        var configuration = mapperConfiguration();
        var statement = configuration.getMappedStatement(LocalAccountMapper.class.getName() + "." + method);
        String input = "' OR 1=1 --";
        var sql = statement.getBoundSql(Map.of("tenantId", "tenant-input-value", lookupParameter, input));

        assertThat(sql.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("tenantId", lookupParameter);
        assertThat(sql.getSql()).doesNotContain(input, "tenant-input-value");
        assertThat(statement.isUseCache()).isFalse();
    }

    @Test
    void shouldMapBothEnableFlagsAndInternalIdentityIntoTheLoginProjection() throws Exception {
        var configuration = mapperConfiguration();
        var statement = configuration.getMappedStatement(
                LocalAccountMapper.class.getName() + ".findLoginAccount");
        var result = statement.getResultMaps().get(0);

        assertThat(result.getType()).isEqualTo(LocalAccountRow.class);
        assertThat(result.getResultMappings().stream()
                .collect(Collectors.toMap(ResultMapping::getProperty, ResultMapping::getColumn)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "userId", "user_id",
                        "tenantId", "tenant_id",
                        "username", "username",
                        "passwordHash", "password_hash",
                        "userEnabled", "user_enabled",
                        "accountEnabled", "account_enabled"));
    }

    private static MybatisConfiguration mapperConfiguration() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String location = properties.getProperty("mybatis-plus.mapper-locations");
        assertThat(location).isNotBlank();

        var resources = new PathMatchingResourcePatternResolver().getResources(location);
        var mapper = Arrays.stream(resources)
                .filter(resource -> "LocalAccountMapper.xml".equals(resource.getFilename()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("配置目录内未发现 LocalAccountMapper.xml"));

        var configuration = new MybatisConfiguration();
        try (var stream = mapper.getInputStream()) {
            new XMLMapperBuilder(stream, configuration, mapper.getDescription(),
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
