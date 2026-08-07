package com.mianmianshi.platform.monitor;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

import java.util.Date;

/**
 * 慢 SQL 日志记录实体 — 存储到 Elasticsearch
 *
 * @author mianmianshi
 */
@Data
@Document(indexName = "slow_sql_log", createIndex = true)
public class SlowSqlRecord {

    @Id
    private String id;

    /** 数据源名称：ds0 / ds1 */
    private String dataSource;

    /** 参数化后的 SQL 语句 */
    private String sql;

    /** 执行次数（统计周期内） */
    private long executeCount;

    /** 最慢执行耗时（毫秒） */
    private long maxTimespan;

    /** 平均执行耗时（毫秒） */
    private double avgTimespan;

    /** 错误次数 */
    private long errorCount;

    /** 首次出现时间 */
    private Date firstTime;

    /** 最近出现时间 */
    private Date lastTime;

    /** 调用栈（连接获取时的堆栈） */
    private String stackTrace;

    /** 告警级别 */
    private String alarmLevel;
}
