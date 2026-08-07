package com.mianmianshi.platform.sharding;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分片键校验拦截器（MyBatis 插件）
 * <p>
 * 分库分表后，所有对分片表的 SQL 必须包含分片键（WHERE 条件），
 * 否则会导致全库广播查询，严重影响性能。
 * <p>
 * 拦截 SELECT/UPDATE/DELETE 语句，校验是否包含分片键，
 * 缺失时记录 WARN 日志（开发环境可配置为直接抛异常）。
 *
 * <p>分片键映射表：
 * <pre>
 *   code_submission       → user_id
 *   question              → id
 *   question_bank_question → question_id
 *   mq_sync_record        → create_time
 * </pre>
 *
 * @author mianmianshi
 */
@Slf4j
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
@ConditionalOnProperty(name = "sharding.enabled", havingValue = "true")
public class ShardingKeyCheckInterceptor implements Interceptor {

    /** 分片表 → 分片键 */
    private static final Map<String, Set<String>> SHARDING_KEY_MAP = new LinkedHashMap<>();

    static {
        SHARDING_KEY_MAP.put("code_submission", new HashSet<>(Collections.singletonList("user_id")));
        SHARDING_KEY_MAP.put("question", new HashSet<>(Collections.singletonList("id")));
        SHARDING_KEY_MAP.put("question_bank_question", new HashSet<>(Collections.singletonList("question_id")));
        SHARDING_KEY_MAP.put("mq_sync_record", new HashSet<>(Collections.singletonList("create_time")));
    }

    /** 是否在缺失分片键时抛异常（开发/测试环境建议 true） */
    private boolean strictMode = false;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);
        MappedStatement ms = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        BoundSql boundSql = handler.getBoundSql();

        SqlCommandType commandType = ms.getSqlCommandType();
        if (commandType == SqlCommandType.INSERT) {
            // INSERT 自动带分片键值，不需校验
            return invocation.proceed();
        }

        String sql = boundSql.getSql().toLowerCase();

        for (Map.Entry<String, Set<String>> entry : SHARDING_KEY_MAP.entrySet()) {
            String table = entry.getKey();
            Set<String> requiredKeys = entry.getValue();

            if (!sql.contains(table)) continue;

            boolean hasShardingKey = false;
            for (String key : requiredKeys) {
                // 检测 WHERE 子句中是否包含分片键
                Pattern pattern = Pattern.compile(
                        "where\\s+.*\\b" + key + "\\b\\s*[=<>]", Pattern.DOTALL);
                if (pattern.matcher(sql).find()
                        || sql.contains(key + " in (")
                        || sql.contains(key + "in(")
                        || sql.contains(key + " = ")
                        || sql.contains(key + "=")) {
                    hasShardingKey = true;
                    break;
                }
            }

            if (!hasShardingKey) {
                String msg = String.format(
                        "[分片键缺失] 表=%s 需要分片键=%s, SQL: %s",
                        table, requiredKeys, truncate(boundSql.getSql(), 300));
                if (strictMode) {
                    throw new IllegalStateException(msg);
                }
                log.warn(msg);
            }
        }

        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        this.strictMode = Boolean.parseBoolean(
                properties.getProperty("strictMode", "false"));
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
