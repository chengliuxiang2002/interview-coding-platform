package com.mianmianshi.platform.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 全量数据迁移工具
 * <p>
 * 在停机窗口期间，将旧单库数据按分片规则批量写入分片库。
 * 使用游标分页 + 线程池并发写入，避免 OOM。
 *
 * <p>使用方式：
 * <pre>
 *   FullDataMigrator migrator = new FullDataMigrator(oldDS, shardingDS);
 *   MigrationResult result = migrator.migrateCodeSubmission(1000);
 *   result.printReport();
 * </pre>
 *
 * @author mianmianshi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullDataMigrator {

    // 注入旧库 DataSource 和 ShardingSphere DataSource
    private final DataSource oldDataSource;
    private final DataSource shardingDataSource;

    private final ExecutorService executor = new ThreadPoolExecutor(
            8, 16, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(2000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    /**
     * 迁移 code_submission 表
     */
    public MigrationResult migrateCodeSubmission(int batchSize) {
        MigrationResult result = new MigrationResult("code_submission");

        try (Connection oldConn = oldDataSource.getConnection();
             Connection newConn = shardingDataSource.getConnection()) {

            long total = countTable(oldConn, "code_submission");
            log.info("[全量迁移] code_submission 共 {} 条，批次大小 {}", total, batchSize);

            long lastId = 0;
            while (true) {
                List<Map<String, Object>> rows = readBatch(oldConn,
                        "code_submission", lastId, batchSize);
                if (rows.isEmpty()) break;

                batchInsert(newConn, rows, result);
                lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();

                result.addSuccess(rows.size());
                log.info("[全量迁移] 进度: {}/{} ({}%)",
                        result.getSuccessCount(), total,
                        String.format("%.1f", result.getSuccessCount() * 100.0 / total));
            }
        } catch (Exception e) {
            log.error("[全量迁移] 迁移失败", e);
            result.addError(e);
        }

        result.finish();
        result.printReport();
        return result;
    }

    /**
     * 迁移 question 表
     */
    public MigrationResult migrateQuestion(int batchSize) {
        return migrateSimple("question", batchSize);
    }

    /**
     * 迁移 question_bank_question 表
     */
    public MigrationResult migrateQuestionBankQuestion(int batchSize) {
        return migrateSimple("question_bank_question", batchSize);
    }

    /**
     * 通用单表迁移（无大字段的简单表）
     */
    private MigrationResult migrateSimple(String tableName, int batchSize) {
        MigrationResult result = new MigrationResult(tableName);

        try (Connection oldConn = oldDataSource.getConnection();
             Connection newConn = shardingDataSource.getConnection()) {

            long total = countTable(oldConn, tableName);
            log.info("[全量迁移] {} 共 {} 条", tableName, total);

            long lastId = 0;
            while (true) {
                List<Map<String, Object>> rows = readBatch(oldConn, tableName, lastId, batchSize);
                if (rows.isEmpty()) break;

                batchInsertSimple(newConn, tableName, rows, result);
                lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();

                result.addSuccess(rows.size());
                log.info("[全量迁移] {} 进度: {}/{}", tableName, result.getSuccessCount(), total);
            }
        } catch (Exception e) {
            log.error("[全量迁移] {} 失败", tableName, e);
            result.addError(e);
        }

        result.finish();
        result.printReport();
        return result;
    }

    // =============== 内部工具方法 ===============

    private long countTable(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM " + table + " WHERE isDelete = 0")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private List<Map<String, Object>> readBatch(Connection conn, String table,
                                                 long lastId, int batchSize) throws SQLException {
        String sql = "SELECT * FROM " + table + " WHERE id > ? AND isDelete = 0" +
                " ORDER BY id ASC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, lastId);
            ps.setInt(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnName(i).toLowerCase(), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /**
     * 批量写入 code_submission（含大字段 code、judgeResult）
     */
    private void batchInsert(Connection newConn, List<Map<String, Object>> rows,
                             MigrationResult result) {
        String sql = "INSERT INTO code_submission " +
                "(id, questionId, userId, language, code, status, " +
                " passedCases, totalCases, executeTimeMs, memoryUsageKb, " +
                " judgeResult, errorMessage, createTime, updateTime, isDelete) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (PreparedStatement ps = newConn.prepareStatement(sql)) {
            for (Map<String, Object> row : rows) {
                ps.setObject(1, row.get("id"));
                ps.setObject(2, row.get("questionid"));
                ps.setObject(3, row.get("userid"));
                ps.setObject(4, row.get("language"));
                ps.setObject(5, row.get("code"));
                ps.setObject(6, row.get("status"));
                ps.setObject(7, row.get("passedcases"));
                ps.setObject(8, row.get("totalcases"));
                ps.setObject(9, row.get("executetimems"));
                ps.setObject(10, row.get("memoryusagekb"));
                ps.setObject(11, row.get("judgeresult"));
                ps.setObject(12, row.get("errormessage"));
                ps.setObject(13, row.get("createtime"));
                ps.setObject(14, row.get("updatetime"));
                ps.setObject(15, row.get("isdelete"));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("[批量写入] 失败", e);
            result.addError(e);
        }
    }

    /**
     * 通用批量写入
     */
    private void batchInsertSimple(Connection newConn, String tableName,
                                    List<Map<String, Object>> rows,
                                    MigrationResult result) {
        if (rows.isEmpty()) return;

        Map<String, Object> sample = rows.get(0);
        Set<String> columns = new LinkedHashSet<>(sample.keySet());

        StringBuilder sqlBuilder = new StringBuilder("INSERT INTO ")
                .append(tableName).append(" (");
        String cols = String.join(", ", columns);
        sqlBuilder.append(cols).append(") VALUES (");
        String placeholders = String.join(", ",
                Collections.nCopies(columns.size(), "?"));
        sqlBuilder.append(placeholders).append(")");

        try (PreparedStatement ps = newConn.prepareStatement(sqlBuilder.toString())) {
            for (Map<String, Object> row : rows) {
                int idx = 1;
                for (String col : columns) {
                    ps.setObject(idx++, row.get(col));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            log.error("[批量写入] {} 失败", tableName, e);
            result.addError(e);
        }
    }
}
