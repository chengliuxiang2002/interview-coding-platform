package com.mianmianshi.platform.monitor;

import com.alibaba.druid.pool.DruidDataSourceStatManager;
import com.alibaba.druid.stat.DruidDataSourceStatManagerMBean;
import com.alibaba.druid.stat.DruidStatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.*;

/**
 * 慢 SQL 采集器
 * <p>
 * 定时从 Druid StatFilter 拉取 SQL 统计信息，
 * 过滤慢查询并存入 ES，超慢 SQL(>3s) 通过 RabbitMQ 触发告警。
 *
 * @author mianmianshi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowSqlCollector {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final long SLOW_THRESHOLD_MS = 500L;
    private static final long CRITICAL_THRESHOLD_MS = 3000L;

    /**
     * 每 30 秒采集一次
     */
    @Scheduled(fixedDelay = 30_000)
    public void collect() {
        try {
            String json = DruidStatService.getInstance().service("/sql.json");
            if (json == null || json.isEmpty()) return;

            JsonNode root = objectMapper.readTree(json);
            if (!root.has("Content")) return;

            JsonNode content = root.get("Content");
            if (!content.isArray()) return;

            List<SlowSqlRecord> records = new ArrayList<>();

            for (JsonNode node : content) {
                long maxTimespan = node.has("MaxTimespan")
                        ? node.get("MaxTimespan").asLong() : 0L;

                if (maxTimespan < SLOW_THRESHOLD_MS) continue;

                SlowSqlRecord record = new SlowSqlRecord();
                record.setId(UUID.randomUUID().toString());
                record.setDataSource(node.has("DataSourceName")
                        ? node.get("DataSourceName").asText() : "unknown");
                record.setSql(node.has("SQL") ? node.get("SQL").asText() : "");
                record.setExecuteCount(node.has("ExecuteCount")
                        ? node.get("ExecuteCount").asLong() : 0L);
                record.setMaxTimespan(maxTimespan);
                record.setAvgTimespan(node.has("AvgTimespan")
                        ? node.get("AvgTimespan").asDouble() : 0.0);
                record.setErrorCount(node.has("ErrorCount")
                        ? node.get("ErrorCount").asLong() : 0L);
                record.setLastTime(new Date());
                record.setStackTrace(node.has("LastStackTrace")
                        ? node.get("LastStackTrace").asText() : "");
                record.setAlarmLevel(maxTimespan >= CRITICAL_THRESHOLD_MS
                        ? "CRITICAL" : "WARNING");

                records.add(record);

                // 超慢 SQL 实时告警
                if (maxTimespan >= CRITICAL_THRESHOLD_MS) {
                    rabbitTemplate.convertAndSend(
                            "slow.sql.alert.critical", record);
                    log.warn("[慢SQL告警-CRITICAL] DS={} Time={}ms SQL={}",
                            record.getDataSource(), maxTimespan,
                            truncate(record.getSql(), 200));
                } else {
                    rabbitTemplate.convertAndSend(
                            "slow.sql.alert.warning", record);
                }
            }

            if (!records.isEmpty()) {
                log.info("[慢SQL采集] 本次采集到 {} 条慢SQL记录", records.size());
            }
        } catch (Exception e) {
            log.error("[慢SQL采集] 采集失败", e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
