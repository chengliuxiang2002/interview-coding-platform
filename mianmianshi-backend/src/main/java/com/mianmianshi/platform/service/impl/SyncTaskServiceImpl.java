package com.mianmianshi.platform.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mianmianshi.platform.mapper.MqSyncRecordMapper;
import com.mianmianshi.platform.model.dto.question.QuestionSyncMessage;
import com.mianmianshi.platform.model.entity.MqSyncRecord;
import com.mianmianshi.platform.service.SyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 同步任务管理 Service 实现
 *
 * @author mianmianshi
 */
@Slf4j
@Service
public class SyncTaskServiceImpl
        extends ServiceImpl<MqSyncRecordMapper, MqSyncRecord>
        implements SyncTaskService {

    @Resource
    private RedissonClient redissonClient;

    private static final String VERSION_KEY_PREFIX = "es:sync:version:";

    @Override
    public MqSyncRecord recordProcessing(Long questionId, String action,
                                          QuestionSyncMessage message) {
        MqSyncRecord record = new MqSyncRecord();
        record.setQuestionId(questionId);
        record.setAction(action);
        record.setMessageBody(JSONUtil.toJsonStr(message));
        record.setVersion(message.getVersion());
        record.setPriority(message.getPriority());
        record.setRetryCount(message.getRetryCount() != null
                ? message.getRetryCount() : 0);
        record.setStatus(MqSyncRecord.Status.PROCESSING.name());
        record.setMessageTime(new Date(message.getTimestamp()));
        record.setProcessStartTime(new Date());
        record.setCreateTime(new Date());
        save(record);
        return record;
    }

    @Override
    public void recordSuccess(Long recordId, long totalMs, long esMs) {
        MqSyncRecord record = new MqSyncRecord();
        record.setId(recordId);
        record.setStatus(MqSyncRecord.Status.SUCCESS.name());
        record.setCostMs(totalMs);
        record.setProcessEndTime(new Date());
        updateById(record);
    }

    @Override
    public void recordSkipped(Long recordId, String reason) {
        MqSyncRecord record = new MqSyncRecord();
        record.setId(recordId);
        record.setStatus(MqSyncRecord.Status.SKIPPED.name());
        record.setErrorMessage(reason);
        record.setProcessEndTime(new Date());
        updateById(record);
    }

    @Override
    public void recordFailed(Long recordId, int retryCount, String errorMsg,
                              String errorStack) {
        MqSyncRecord record = new MqSyncRecord();
        record.setId(recordId);
        record.setRetryCount(retryCount);
        record.setErrorMessage(errorMsg);
        record.setErrorStack(errorStack);

        // 重试耗尽后标记为 FAILED
        if (retryCount >= 2) {
            record.setStatus(MqSyncRecord.Status.FAILED.name());
            record.setProcessEndTime(new Date());
        }

        updateById(record);
    }

    @Override
    public Long getLastProcessedVersion(Long questionId) {
        // 优先从 Redis 读（热数据），Redis 未命中则查 MySQL
        String key = VERSION_KEY_PREFIX + questionId;
        RBucket<Long> bucket = redissonClient.getBucket(key);
        Long version = bucket.get();
        if (version != null) {
            return version;
        }
        // Redis 未命中，查 MySQL
        version = baseMapper.selectLastProcessedVersion(questionId);
        if (version != null) {
            bucket.set(version, 30, TimeUnit.MINUTES);
        }
        return version;
    }

    @Override
    public void updateLastProcessedVersion(Long questionId, Long version) {
        if (version == null) {
            return;
        }
        String key = VERSION_KEY_PREFIX + questionId;
        RBucket<Long> bucket = redissonClient.getBucket(key);
        bucket.set(version, 30, TimeUnit.MINUTES);
    }

    @Override
    public Map<String, Object> getRecentStats() {
        return baseMapper.selectRecentStats();
    }

    @Override
    public Page<MqSyncRecord> listByStatus(String status, long current, long size) {
        LambdaQueryWrapper<MqSyncRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq(MqSyncRecord::getStatus, status);
        }
        wrapper.orderByDesc(MqSyncRecord::getCreateTime);
        return page(new Page<>(current, size), wrapper);
    }

    @Override
    public MqSyncRecord getByQuestionId(Long questionId) {
        LambdaQueryWrapper<MqSyncRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MqSyncRecord::getQuestionId, questionId);
        wrapper.orderByDesc(MqSyncRecord::getCreateTime);
        wrapper.last("LIMIT 1");
        return getOne(wrapper);
    }
}
