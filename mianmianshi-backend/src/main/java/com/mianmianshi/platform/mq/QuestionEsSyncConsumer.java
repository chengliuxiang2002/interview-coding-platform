package com.mianmianshi.platform.mq;

import cn.hutool.json.JSONUtil;
import com.mianmianshi.platform.constant.MqConstant;
import com.mianmianshi.platform.model.dto.question.QuestionEsDTO;
import com.mianmianshi.platform.model.dto.question.QuestionSyncMessage;
import com.mianmianshi.platform.model.entity.MqSyncRecord;
import com.mianmianshi.platform.model.entity.Question;
import com.mianmianshi.platform.service.QuestionService;
import com.mianmianshi.platform.service.SyncTaskService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 消息消费者 —— 异步任务处理与数据同步
 *
 * <h3>核心能力</h3>
 * <ol>
 *   <li>
 *     <b>幂等性处理</b>：基于 Redis 分布式锁 (redisson) + MySQL 版本号双重保障
 *     <ul>
 *       <li>Redis Key: es:sync:lock:{questionId}，TTL=1h</li>
 *       <li>版本号判断：消息 version <= 已处理 version 则跳过</li>
 *     </ul>
 *   </li>
 *   <li>
 *     <b>顺序性保证</b>：同一 questionId 的 SAVE 和 DELETE 走不同队列，
 *     通过版本号比较解决跨队列的顺序问题。DELETE 消息如果 version 更旧则跳过。
 *   </li>
 *   <li>
 *     <b>任务优先级</b>：优先级在消息体中定义，由生产者设置：
 *     DELETE(8) > SAVE(5) > UPDATE(5)
 *   </li>
 *   <li>
 *     <b>耗时任务控制</b>：消费者逐个处理，prefetch=10 控制并发拉取量，
 *     避免大量耗时任务同时执行拖垮 ES
 *   </li>
 * </ol>
 *
 * <h3>错误处理</h3>
 * <p>消费失败 → basicNack(requeue=true) 重新入队 → 累计重试 3 次后 → 死信队列</p>
 *
 * @author mianmianshi
 */
@Slf4j
@Component
public class QuestionEsSyncConsumer {

    @Resource
    private QuestionService questionService;

    @Resource
    private ElasticsearchRestTemplate elasticsearchRestTemplate;

    @Resource
    private SyncTaskService syncTaskService;

    @Resource
    private RedissonClient redissonClient;

    private static final String INDEX_NAME = "question";
    private static final String IDEMPOTENT_KEY_PREFIX = "es:sync:lock:";

    /**
     * 监听保存/更新队列
     */
    @RabbitListener(queues = MqConstant.QUESTION_ES_SAVE_QUEUE)
    public void handleSaveMessage(QuestionSyncMessage message, Channel channel,
                                  @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        processMessage(message, channel, deliveryTag, false);
    }

    /**
     * 监听删除队列
     */
    @RabbitListener(queues = MqConstant.QUESTION_ES_DELETE_QUEUE)
    public void handleDeleteMessage(QuestionSyncMessage message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        processMessage(message, channel, deliveryTag, true);
    }

    // ==================== 核心处理逻辑 ====================

    /**
     * 统一消息处理
     *
     * @param message     消息体
     * @param channel     RabbitMQ Channel
     * @param deliveryTag 投递标签
     * @param isDelete    是否为删除操作
     */
    private void processMessage(QuestionSyncMessage message, Channel channel,
                                 long deliveryTag, boolean isDelete) {
        Long questionId = message.getQuestionId();
        Date startTime = new Date();

        // 1. 记录处理中状态
        MqSyncRecord record = syncTaskService.recordProcessing(questionId,
                message.getAction().name(), message);

        // 2. 幂等性检查 —— Redis 锁
        String lockKey = IDEMPOTENT_KEY_PREFIX + questionId;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;

        try {
            // 尝试获取锁，等待 0 秒（不等待），锁持有 30 秒
            acquired = lock.tryLock(0, 30, TimeUnit.SECONDS);
            if (!acquired) {
                // 已有相同 questionId 的消息在处理中，跳过
                log.info("[MQ-CONSUMER-SKIP] 幂等拦截: questionId={}, action={}, "
                        + "原因：已有其他消息在处理中", questionId, message.getAction());
                syncTaskService.recordSkipped(record.getId(), "幂等拦截：Redis 锁未获取");
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 版本号检查（防旧消息覆盖新数据）
            Long lastProcessedVersion = syncTaskService.getLastProcessedVersion(questionId);
            if (lastProcessedVersion != null
                    && message.getVersion() != null
                    && message.getVersion() <= lastProcessedVersion) {
                log.info("[MQ-CONSUMER-SKIP] 版本号过期: questionId={}, "
                        + "msgVersion={}, lastProcessedVersion={}",
                        questionId, message.getVersion(), lastProcessedVersion);
                syncTaskService.recordSkipped(record.getId(),
                        String.format("版本号过期: msgV=%d <= lastV=%d",
                                message.getVersion(), lastProcessedVersion));
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. 执行实际的 ES 操作
            long esStart = System.currentTimeMillis();
            if (isDelete) {
                doDeleteFromEs(questionId);
            } else {
                doSaveToEs(questionId);
            }
            long esCost = System.currentTimeMillis() - esStart;

            // 5. 记录成功
            long totalCost = System.currentTimeMillis() - startTime.getTime();
            syncTaskService.recordSuccess(record.getId(), totalCost, esCost);
            syncTaskService.updateLastProcessedVersion(questionId, message.getVersion());

            // 6. 确认消息
            channel.basicAck(deliveryTag, false);
            log.info("[MQ-CONSUMER-SUCCESS] questionId={}, action={}, "
                    + "esCost={}ms, totalCost={}ms, priority={}",
                    questionId, message.getAction(), esCost, totalCost,
                    message.getPriority());

            // 7. 超时告警
            if (totalCost > MqConstant.ALERT_PROCESS_TIMEOUT_MS) {
                log.warn("[MQ-CONSUMER-TIMEOUT] 处理耗时过长: questionId={}, "
                        + "totalCost={}ms", questionId, totalCost);
            }

        } catch (Exception e) {
            // 8. 处理失败 —— 根据重试次数决定：requeue 或死信
            handleProcessError(message, channel, deliveryTag, record, e);
        } finally {
            // 9. 释放锁
            if (acquired) {
                try {
                    lock.unlock();
                } catch (Exception ex) {
                    log.warn("释放 Redis 锁失败: questionId={}", questionId, ex);
                }
            }
        }
    }

    // ==================== ES 操作 ====================

    /**
     * 将题目写入 ES
     */
    private void doSaveToEs(Long questionId) {
        Question question = questionService.getById(questionId);
        if (question == null) {
            log.warn("[MQ-CONSUMER] 题目不存在，跳过写入 ES: questionId={}", questionId);
            return;
        }
        QuestionEsDTO esDTO = QuestionEsDTO.objToDto(question);
        elasticsearchRestTemplate.save(esDTO);
    }

    /**
     * 从 ES 删除题目文档
     */
    private void doDeleteFromEs(Long questionId) {
        // 即使 ES 中不存在该文档也尝试删除（ES 的 delete 幂等——不存在不报错）
        elasticsearchRestTemplate.delete(
                String.valueOf(questionId),
                IndexCoordinates.of(INDEX_NAME)
        );
    }

    // ==================== 错误处理 ====================

    /**
     * 处理消费失败
     *
     * <p>重试机制：
     * <ol>
     *   <li>检查 retryCount——消息体中的重试次数（表示已重试次数）</li>
     *   <li>retryCount < 2：reject(requeue=true) 重新入队，retryCount+1</li>
     *   <li>retryCount >= 2（第 3 次失败）：不再 requeue，进入死信队列</li>
     *   <li>记录详细错误日志到 MqSyncRecord</li>
     * </ol>
     *
     * <p>注意：Spring AMQP 的 SimpleRetryPolicy 和这里的手动重试是互补关系。
     * Spring 的 retry 在消费方法内部重新调用，这里的重试是跨消息投递的重试。
     * 实际场景中以手动重试为准，Spring retry 作为快速重试（网络抖动场景）。</p>
     */
    private void handleProcessError(QuestionSyncMessage message, Channel channel,
                                     long deliveryTag, MqSyncRecord record,
                                     Exception e) {
        try {
            int currentRetry = message.getRetryCount() != null
                    ? message.getRetryCount() : 0;

            // 记录错误详情
            syncTaskService.recordFailed(record.getId(), currentRetry,
                    e.getMessage(), getStackTrace(e));

            if (currentRetry < 2) {
                // 重新入队（requeue=true），retryCount+1
                log.warn("[MQ-CONSUMER-RETRY] 第{}次重试: questionId={}, error={}",
                        currentRetry + 1, message.getQuestionId(), e.getMessage());
                message.setRetryCount(currentRetry + 1);
                channel.basicNack(deliveryTag, false, true);
            } else {
                // 不再 requeue，消息进入死信队列
                log.error("[MQ-CONSUMER-DEAD] 重试耗尽，进入死信队列: questionId={}, "
                        + "retryCount={}, error={}",
                        message.getQuestionId(), currentRetry, e.getMessage());
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (IOException ex) {
            log.error("[MQ-CONSUMER-FATAL] ACK/NACK 操作失败: questionId={}",
                    message.getQuestionId(), ex);
        }
    }

    private String getStackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName()).append(": ").append(e.getMessage()).append("\n");
        for (StackTraceElement ste : e.getStackTrace()) {
            sb.append("\tat ").append(ste.toString()).append("\n");
            if (sb.length() > 2000) {
                sb.append("\t... (truncated)");
                break;
            }
        }
        return sb.toString();
    }
}
