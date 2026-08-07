package com.mianmianshi.platform.migration;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 数据一致性校验器
 * <p>
 * 在数据迁移和灰度期间，对比旧库和新库（分片库）的数据，
 * 通过逐条 MD5 校验确保迁移后数据 100% 一致。
 *
 * @author mianmianshi
 */
@Slf4j
public class DataConsistencyVerifier {

    /**
     * 校验结果
     */
    public static class VerifyResult {
        private final String tableName;
        private long totalRows;
        private long matchedRows;
        private long mismatchedRows;
        private long missingInNew;
        private long missingInOld;
        private String firstMismatchDetail;

        public VerifyResult(String tableName) {
            this.tableName = tableName;
        }

        public boolean isAllMatched() {
            return mismatchedRows == 0 && missingInNew == 0 && missingInOld == 0;
        }

        public String getTableName() { return tableName; }
        public long getTotalRows() { return totalRows; }
        public long getMatchedRows() { return matchedRows; }
        public long getMismatchedRows() { return mismatchedRows; }
        public long getMissingInNew() { return missingInNew; }
        public long getMissingInOld() { return missingInOld; }
        public String getFirstMismatchDetail() { return firstMismatchDetail; }

        public void setTotalRows(long totalRows) { this.totalRows = totalRows; }
        public void addMatched() { this.matchedRows++; }
        public void addMismatch(String detail) {
            this.mismatchedRows++;
            if (this.firstMismatchDetail == null) {
                this.firstMismatchDetail = detail;
            }
        }
        public void addMissingInNew() { this.missingInNew++; }
        public void addMissingInOld() { this.missingInOld++; }
    }

    /**
     * 计算一行数据的 MD5
     */
    public static String md5(Map<String, Object> row) {
        String concat = row.values().stream()
                .map(v -> v == null ? "NULL" : v.toString())
                .reduce("", (a, b) -> a + "|" + b);
        return org.apache.commons.codec.digest.DigestUtils.md5Hex(concat);
    }

    /**
     * 打印校验报告
     */
    public static void printReport(VerifyResult result) {
        log.info("==================== 数据校验报告 ====================");
        log.info("表名: {}", result.getTableName());
        log.info("总行数: {}", result.getTotalRows());
        log.info("一致: {} | 不一致: {} | 新库缺失: {} | 旧库缺失: {}",
                result.getMatchedRows(), result.getMismatchedRows(),
                result.getMissingInNew(), result.getMissingInOld());
        if (result.getFirstMismatchDetail() != null) {
            log.info("首个不一致记录: {}", result.getFirstMismatchDetail());
        }
        log.info("校验结果: {}", result.isAllMatched() ? "PASS" : "FAIL");
        log.info("======================================================");
    }
}
