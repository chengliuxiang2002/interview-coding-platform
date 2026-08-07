package com.mianmianshi.platform.monitor.alarm;

import com.mianmianshi.platform.monitor.SlowSqlRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 慢 SQL 告警通知处理器
 * <p>
 * 消费 RabbitMQ 中的告警消息，根据级别分发到不同通知渠道：
 * <ul>
 *   <li>CRITICAL → 企业微信 + Sentinel 熔断降级</li>
 *   <li>WARNING → 邮件通知</li>
 *   <li>INFO → 仅日志记录</li>
 * </ul>
 *
 * @author mianmianshi
 */
@Slf4j
@Component
public class AlarmNotificationHandler {

    /**
     * 严重告警 — 立即通知 + 自动熔断
     */
    @RabbitListener(queues = "slow.sql.alert.critical")
    public void handleCritical(SlowSqlRecord record) {
        log.error("========== [严重慢SQL告警] ==========");
        log.error("数据源: {}", record.getDataSource());
        log.error("耗时: {}ms (最长) / {}ms (平均)", record.getMaxTimespan(), record.getAvgTimespan());
        log.error("执行次数: {} | 错误次数: {}", record.getExecuteCount(), record.getErrorCount());
        log.error("SQL: {}", record.getSql());
        log.error("调用栈: {}", record.getStackTrace());

        // TODO: 接入企业微信机器人通知
        // weChatBot.sendMarkdown(buildAlertMessage(record));

        // TODO: 触发 Sentinel 降级
        // SentinelDegradeManager.degrade(identifyResource(record));
    }

    /**
     * 一般告警 — 邮件通知
     */
    @RabbitListener(queues = "slow.sql.alert.warning")
    public void handleWarning(SlowSqlRecord record) {
        log.warn("[一般慢SQL告警] DS={} Time={}ms SQL={}",
                record.getDataSource(), record.getMaxTimespan(), record.getSql());

        // TODO: 接入邮件服务
        // mailService.sendSlowSqlAlert(record);
    }
}
