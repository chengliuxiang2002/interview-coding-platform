package com.mianmianshi.platform.monitor.alarm;

import com.mianmianshi.platform.monitor.SlowSqlRecord;

/**
 * 告警级别
 */
public enum AlarmLevel {
    /** 仅记录日志 */
    INFO,
    /** 邮件通知 */
    WARNING,
    /** 短信/企业微信 + 自动熔断 */
    CRITICAL
}
