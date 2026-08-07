package com.mianmianshi.platform.sharding;

import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * ShardingSphere 分库分表配置（Java 方式，支持动态切换）
 * <p>
 * 当 sharding.enabled=true 时激活分库分表 DataSource；
 * 否则使用 application.yml 中的单库 DataSource。
 *
 * <p>与 YAML 配置 (application-sharding.yml) 互斥，二选一。
 * 推荐生产环境用 YAML 配置（可 Nacos 动态下发）；
 * 保持此类作为备选方案，方便本地开发和调试。
 *
 * @author mianmianshi
 */
@Configuration
@ConditionalOnProperty(name = "sharding.config-mode", havingValue = "java", matchIfMissing = false)
public class ShardingSphereConfig {

    // ShardingSphere 5.x 的 Java API 配置可通过 ShardingSphereDataSourceFactory 构建;
    // 推荐在 Spring Boot 中直接使用 YAML 配置（application-sharding.yml），
    // 此处保留 Java 配置入口以支持灵活切换。
    //
    // 如使用 Java 配置，可在此处构建：
    //   Map<String, DataSource> dataSourceMap = createDataSourceMap();
    //   Collection<ShardingTableRuleConfiguration> tableRules = createTableRules();
    //   ShardingRuleConfiguration config = new ShardingRuleConfiguration(tableRules, ...);
    //   return ShardingSphereDataSourceFactory.createDataSource(dataSourceMap, config, props);

    /**
     * 创建 Druid 数据源（供 ShardingSphere 管理）
     */
    public static DruidDataSource createDruidDataSource(String url, String username, String password) {
        DruidDataSource ds = new DruidDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 分库分表场景：每库连接数不宜过大
        ds.setInitialSize(10);
        ds.setMinIdle(10);
        ds.setMaxActive(100);
        ds.setMaxWait(2000);
        ds.setTimeBetweenEvictionRunsMillis(60000);
        ds.setMinEvictableIdleTimeMillis(300000);
        ds.setValidationQuery("SELECT 1");
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(false);
        ds.setTestOnReturn(false);
        ds.setKeepAlive(true);

        // 启用监控 Filter
        try {
            ds.setFilters("stat,wall,slf4j");
        } catch (SQLException e) {
            throw new RuntimeException("Druid filter init failed", e);
        }

        // 慢 SQL 阈值 500ms
        ds.addConnectionProperty("druid.stat.slowSqlMillis", "500");
        ds.addConnectionProperty("druid.stat.logSlowSql", "true");

        return ds;
    }
}
