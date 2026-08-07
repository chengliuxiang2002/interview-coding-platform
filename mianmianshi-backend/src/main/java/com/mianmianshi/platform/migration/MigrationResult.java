package com.mianmianshi.platform.migration;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 全量数据迁移结果统计
 */
@Slf4j
public class MigrationResult {
    private final String tableName;
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private long startTime;
    private long endTime;

    public MigrationResult(String tableName) {
        this.tableName = tableName;
        this.startTime = System.currentTimeMillis();
    }

    public void addSuccess(int count) {
        successCount.addAndGet(count);
    }

    public void addError(Exception e) {
        errorCount.incrementAndGet();
        log.error("[迁移异常] {}: {}", tableName, e.getMessage());
    }

    public void finish() {
        this.endTime = System.currentTimeMillis();
    }

    public void printReport() {
        long duration = (endTime - startTime) / 1000;
        log.info("==================== 迁移报告 ====================");
        log.info("表名: {}", tableName);
        log.info("成功: {} | 失败: {}", successCount.get(), errorCount.get());
        log.info("耗时: {} 秒", duration);
        log.info("===================================================");
    }

    public long getSuccessCount() { return successCount.get(); }
    public long getErrorCount() { return errorCount.get(); }
    public String getTableName() { return tableName; }
}
