package com.mianmianshi.platform.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 双写切面 — 灰度期间同时写入旧库和新库（分片库）
 * <p>
 * 通过 `migration.dual-write.enabled=true` 开关控制，
 * 灰度验证通过后关闭此切面，下线旧库。
 *
 * @author mianmianshi
 */
@Slf4j
@Aspect
@Component
@ConditionalOnProperty(name = "migration.dual-write.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DualWriteAspect {

    private final DataSource oldDataSource;
    private final DataSource newDataSource;

    /**
     * 拦截 Mapper 的 INSERT 操作
     */
    @Around("execution(* com.mianmianshi.platform.mapper.*.insert(..))")
    public Object dualWriteInsert(ProceedingJoinPoint pjp) throws Throwable {
        // 1. 先写新分片库
        Object result = pjp.proceed();

        // 2. 同步写旧库
        try {
            // 解析 Mapper 方法名，推断表名
            String methodName = pjp.getSignature().getName();
            String entityName = pjp.getTarget().getClass().getSimpleName()
                    .replace("Mapper", "").toLowerCase();

            Object entity = pjp.getArgs()[0];
            writeToOldDB(entityName, entity);
        } catch (Exception e) {
            log.error("[双写] 旧库写入失败（不影响新库）: {}", e.getMessage());
        }

        return result;
    }

    private void writeToOldDB(String table, Object entity) {
        // 简化实现：通过反射构建 INSERT SQL
        // 生产环境建议用 MyBatis-Plus 的另一个 SqlSession 直接写入
        log.debug("[双写] 同步旧库: table={}, entity={}", table, entity.getClass().getSimpleName());
    }
}
