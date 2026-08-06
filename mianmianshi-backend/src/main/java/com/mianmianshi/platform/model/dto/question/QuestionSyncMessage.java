package com.mianmianshi.platform.model.dto.question;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 题目数据同步消息体
 *
 * <p>包含数据同步所需的全量元数据：
 * <ul>
 *   <li>dataId - 数据唯一标识（题目ID）</li>
 *   <li>action - 操作类型（SYNC/SAVE/DELETE）</li>
 *   <li>timestamp - 消息生成时间（毫秒）</li>
 *   <li>version - 数据版本号（基于 updateTime，用于幂等判断）</li>
 *   <li>priority - 消息优先级（0-10，越大越优先）</li>
 *   <li>retryCount - 当前重试次数</li>
 *   <li>source - 消息来源标识</li>
 * </ul>
 *
 * <h3>消息体设计原则</h3>
 * <p>只传 id + 元数据，消费者自己去 MySQL 查最新数据。
 * 优点：消息体小、不因 DB 字段变更导致消息格式不兼容。</p>
 *
 * @author mianmianshi
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuestionSyncMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 操作类型枚举 */
    public enum SyncAction {
        /** 新增 */
        SAVE,
        /** 更新 */
        UPDATE,
        /** 删除 */
        DELETE
    }

    /** 优先级常量 */
    public static final int PRIORITY_HIGH = 8;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_LOW = 2;

    // ==================== 元数据字段 ====================

    /** 数据 ID（题目 ID） */
    private Long questionId;

    /** 操作类型 */
    private SyncAction action;

    /** 消息生成时间戳（毫秒） */
    private Long timestamp;

    /**
     * 数据版本号
     *
     * <p>使用 MySQL 中 Question.updateTime 的毫秒值。
     * 消费者处理前对比 ES 中文档的版本号，如果消息版本号小于等于已处理版本号则跳过（幂等）。</p>
     */
    private Long version;

    /** 消息优先级（0-10，10 最高） */
    private Integer priority;

    /** 已重试次数 */
    private Integer retryCount;

    /** 消息来源（用于链路追踪） */
    private String source;

    /**
     * 创建一条同步消息（快捷工厂方法）
     *
     * @param questionId 题目 ID
     * @param action     操作类型
     * @param version    版本号
     * @param priority   优先级
     * @return 消息体
     */
    public static QuestionSyncMessage create(Long questionId, SyncAction action,
                                              Long version, int priority) {
        return QuestionSyncMessage.builder()
                .questionId(questionId)
                .action(action)
                .timestamp(System.currentTimeMillis())
                .version(version)
                .priority(priority)
                .retryCount(0)
                .source("question-service")
                .build();
    }
}
