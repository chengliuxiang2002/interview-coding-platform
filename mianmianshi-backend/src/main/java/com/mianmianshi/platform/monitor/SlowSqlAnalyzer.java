package com.mianmianshi.platform.monitor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 慢 SQL 分析器 — 生成日报/周报
 *
 * @author mianmianshi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlowSqlAnalyzer {

    /**
     * 生成慢 SQL 摘要（单条 SQL 的 Top-N 统计）
     */
    public SlowSqlSummary summarize(List<SlowSqlRecord> records) {
        int total = records.size();
        long totalExec = records.stream().mapToLong(SlowSqlRecord::getExecuteCount).sum();
        long totalErrors = records.stream().mapToLong(SlowSqlRecord::getErrorCount).sum();
        double avgTime = records.stream().mapToDouble(SlowSqlRecord::getAvgTimespan)
                .average().orElse(0.0);
        long maxTime = records.stream().mapToLong(SlowSqlRecord::getMaxTimespan)
                .max().orElse(0L);

        // Top 10 按最慢耗时
        List<SlowSqlRecord> top10Slow = records.stream()
                .sorted(Comparator.comparingLong(SlowSqlRecord::getMaxTimespan).reversed())
                .limit(10).collect(Collectors.toList());

        // Top 10 按执行频率
        List<SlowSqlRecord> top10Freq = records.stream()
                .sorted(Comparator.comparingLong(SlowSqlRecord::getExecuteCount).reversed())
                .limit(10).collect(Collectors.toList());

        // 按数据源分布
        Map<String, Long> dsDistribution = records.stream()
                .collect(Collectors.groupingBy(SlowSqlRecord::getDataSource, Collectors.counting()));

        return new SlowSqlSummary(total, totalExec, totalErrors, avgTime, maxTime,
                top10Slow, top10Freq, dsDistribution,
                generateSuggestions(records));
    }

    /**
     * 基于规则生成优化建议
     */
    private List<String> generateSuggestions(List<SlowSqlRecord> records) {
        List<String> suggestions = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (SlowSqlRecord r : records) {
            String sql = r.getSql().toUpperCase().trim();

            if (sql.contains("SELECT *") && seen.add("SELECT_STAR")) {
                suggestions.add("发现 SELECT * 查询，建议明确指定字段以利用覆盖索引、减少网络传输");
            }
            if ((sql.contains("LIKE '%") || sql.contains("LIKE'%"))
                    && seen.add("PREFIX_LIKE")) {
                suggestions.add("发现前缀模糊查询(%xxx)，无法使用索引，建议改用 ES 全文检索");
            }
            if (!sql.contains("WHERE") && seen.add("NO_WHERE")) {
                suggestions.add("发现无 WHERE 条件查询，存在全表扫描风险");
            }
            if (sql.contains("ORDER BY") && r.getAvgTimespan() > 1000
                    && seen.add("FILESORT")) {
                suggestions.add("发现慢排序查询(>1s)，建议建联合索引覆盖排序字段消除 filesort");
            }
            if (r.getErrorCount() > 10 && seen.add("HIGH_ERROR")) {
                suggestions.add("发现高频错误 SQL (错误>10次): " + r.getSql());
            }
        }
        return suggestions;
    }

    /**
     * 慢 SQL 摘要 DTO
     */
    public static class SlowSqlSummary {
        public final int totalSlowSql;
        public final long totalExecutions;
        public final long totalErrors;
        public final double avgTimeMs;
        public final long maxTimeMs;
        public final List<SlowSqlRecord> top10ByMaxTime;
        public final List<SlowSqlRecord> top10ByFrequency;
        public final Map<String, Long> dataSourceDistribution;
        public final List<String> optimizationSuggestions;

        public SlowSqlSummary(int totalSlowSql, long totalExecutions, long totalErrors,
                              double avgTimeMs, long maxTimeMs,
                              List<SlowSqlRecord> top10ByMaxTime,
                              List<SlowSqlRecord> top10ByFrequency,
                              Map<String, Long> dataSourceDistribution,
                              List<String> optimizationSuggestions) {
            this.totalSlowSql = totalSlowSql;
            this.totalExecutions = totalExecutions;
            this.totalErrors = totalErrors;
            this.avgTimeMs = avgTimeMs;
            this.maxTimeMs = maxTimeMs;
            this.top10ByMaxTime = top10ByMaxTime;
            this.top10ByFrequency = top10ByFrequency;
            this.dataSourceDistribution = dataSourceDistribution;
            this.optimizationSuggestions = optimizationSuggestions;
        }
    }
}
