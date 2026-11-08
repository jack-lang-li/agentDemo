package com.example.agent.business;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.example.agent.business.order.adapter.persistence.mapper.OrderQueryMapper;
import com.example.agent.business.logistics.adapter.persistence.mapper.LogisticsQueryMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ResultMapping;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 解析真实 Mapper 资源，检查安全范围、参数绑定和投影；不替代 MySQL 执行验证。 */
class BusinessDataMapperConfigurationTest {

    @ParameterizedTest
    @CsvSource({"order,orderId", "logistics,trackingNo"})
    void shouldBindTenantOwnerAndIdentifierAsPreparedParameters(String kind, String identifier) throws Exception {
        var statement = statement(kind);
        String untrustedId = "' OR 1=1 --";
        var sql = statement.getBoundSql(Map.of(
                "tenantId", "tenant-input-value", "ownerUserId", "owner-input-value", identifier, untrustedId));

        assertThat(sql.getParameterMappings()).extracting(ParameterMapping::getProperty)
                .containsExactly("tenantId", "ownerUserId", identifier);
        assertThat(sql.getSql()).doesNotContain(untrustedId, "tenant-input-value", "owner-input-value");
        assertThat(statement.isUseCache()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"order,o", "logistics,s"})
    void shouldRequireTenantAndOwnerPredicatesAndTenantScopedJoins(String kind, String alias) throws Exception {
        var bound = statement(kind).getBoundSql(Map.of(
                "tenantId", "t", "ownerUserId", "u", "orderId", "1", "trackingNo", "track"));
        String sql = bound.getSql().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String where = sql.substring(sql.indexOf(" where "));

        assertThat(where).contains(alias + ".tenant_id = ?", "o.owner_user_id = ?")
                .doesNotContain(" or ");
        assertThat(sql).containsPattern("(?:s\\.tenant_id = o\\.tenant_id|o\\.tenant_id = s\\.tenant_id)");
        assertThat(sql).containsPattern("(?:s\\.order_id = o\\.order_id|o\\.order_id = s\\.order_id)");
        if (kind.equals("order")) {
            assertThat(sql).contains("left join");
            assertThat(where).contains("o.order_id = ?");
        } else {
            assertThat(sql).contains("inner join");
            assertThat(where).contains("s.tracking_no = ?");
        }
    }

    @ParameterizedTest
    @CsvSource({"order", "logistics"})
    void shouldProjectOnlyTheExpectedBusinessFields(String kind) throws Exception {
        var result = statement(kind).getResultMaps().get(0);
        assertThat(result.getType().getSimpleName())
                .isEqualTo(kind.equals("order") ? "OrderQueryRow" : "LogisticsQueryRow");
        Map<String, String> expected = kind.equals("order")
                ? Map.of("orderId", "order_id", "shippingStatus", "shipping_status",
                        "carrier", "carrier", "trackingNo", "tracking_no")
                : Map.of("trackingNo", "tracking_no", "currentLocation", "current_location",
                        "latestEvent", "latest_event");
        assertThat(result.getResultMappings().stream()
                .collect(Collectors.toMap(ResultMapping::getProperty, ResultMapping::getColumn)))
                .containsExactlyInAnyOrderEntriesOf(expected);
    }

    private static MappedStatement statement(String kind) throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var properties = yaml.getObject();
        assertThat(properties).isNotNull();
        String location = properties.getProperty("mybatis-plus.mapper-locations");
        assertThat(location).isNotBlank();
        String filename = kind.equals("order") ? "OrderQueryMapper.xml" : "LogisticsQueryMapper.xml";
        var resource = Arrays.stream(new PathMatchingResourcePatternResolver().getResources(location))
                .filter(candidate -> filename.equals(candidate.getFilename())).findFirst().orElseThrow();
        var configuration = new MybatisConfiguration();
        try (var stream = resource.getInputStream()) {
            new XMLMapperBuilder(stream, configuration, resource.getDescription(),
                    configuration.getSqlFragments()).parse();
        }
        Class<?> mapper = kind.equals("order") ? OrderQueryMapper.class : LogisticsQueryMapper.class;
        String method = kind.equals("order") ? "findOwnedOrder" : "findOwnedLogistics";
        return configuration.getMappedStatement(mapper.getName() + "." + method);
    }
}
