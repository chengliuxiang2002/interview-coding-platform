package com.mianmianshi.platform.service;

import com.mianmianshi.platform.model.dto.question.QuestionSyncMessage;
import com.mianmianshi.platform.model.entity.MqSyncRecord;

/**
 * 同步任务管理 Service
 *
 * <p>提供任务状态追踪、指标统计、幂等性判断等能力</p>
 *
 * @author mianmianshi
 */
public interface SyncTaskService {

    /**
     * 记录消息进入处理
     *
     * @param questionId 数据 ID
     * @param action     操作类型
     * @param message    消息体
     * @return 新建的记录
     */
    MqSyncRecord recordProcessing(Long questionId, String action,
                                   QuestionSyncMessage message);

    /**
     * 记录处理成功
     *
     * @param recordId 记录 ID
     * @param totalMs  总耗时
     * @param esMs     ES 操作耗时
     */
    void recordSuccess(Long recordId, long totalMs, long esMs);

    /**
     * 记录幂等跳过
     *
     * @param recordId 记录 ID
     * @param reason   跳过原因
     */
    void recordSkipped(Long recordId, String reason);

    /**
     * 记录处理失败
     *
     * @param recordId    记录 ID
     * @param retryCount  当前重试次数
     * @param errorMsg    错误消息
     * @param errorStack  错误堆栈
     */
    void recordFailed(Long recordId, int retryCount, String errorMsg,
                       String errorStack);

    /**
     * 获取某数据最后成功处理的版本号（幂等性判断用）
     *
     * @param questionId 数据 ID
     * @return 版本号，可能为 null
     */
    Long getLastProcessedVersion(Long questionId);

    /**
     * 更新最后成功处理版本号（Redis 缓存）
     *
     * @param questionId 数据 ID
     * @param version    版本号
     */
    void updateLastProcessedVersion(Long questionId, Long version);

    /**
     * 获取最近 1 分钟处理统计
     *
     * @return 统计信息
     */
    java.util.Map<String, Object> getRecentStats();

    /**
     * 按状态分页查询同步记录
     */
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<MqSyncRecord>
            listByStatus(String status, long current, long size);

    /**
     * 根据 questionId 查询同步记录
     */
    MqSyncRecord getByQuestionId(Long questionId);
}
