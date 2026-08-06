package com.mianmianshi.platform.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * MQ 同步任务执行记录
 *
 * <p>记录每一次 MQ 消息的处理状态，用于：
 * <ul>
 *   <li>追踪单个同步任务的处理进度</li>
 *   <li>统计成功率、延迟、失败原因</li>
 *   <li>支持监控告警和管理后台查询</li>
 * </ul>
 *
 * @author mianmianshi
 */
@Data
@TableName(value = "mq_sync_record")
public class MqSyncRecord implements Serializable {

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;

    /** 任务状态 */
    public enum Status {
        /** 处理中 */
        PROCESSING,
        /** 成功 */
        SUCCESS,
        /** 失败（已进入死信队列） */
        FAILED,
        /** 跳过（幂等拦截） */
        SKIPPED
    }

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 数据 ID */
    private Long questionId;

    /** 操作类型：SAVE / UPDATE / DELETE */
    private String action;

    /** 消息 ID（RabbitMQ messageId） */
    private String messageId;

    /** 消息体 JSON 快照 */
    private String messageBody;

    /** 消息版本号 */
    private Long version;

    /** 消息优先级 */
    private Integer priority;

    /** 处理状态 */
    private String status;

    /** 重试次数 */
    private Integer retryCount;

    /** 错误信息 */
    private String errorMessage;

    /** 错误堆栈 */
    private String errorStack;

    /** 处理耗时（毫秒） */
    private Long costMs;

    /** 消息生成时间 */
    private Date messageTime;

    /** 处理开始时间 */
    private Date processStartTime;

    /** 处理结束时间 */
    private Date processEndTime;

    /** 记录创建时间 */
    private Date createTime;
}
