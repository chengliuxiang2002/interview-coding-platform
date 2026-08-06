package com.mianmianshi.platform.mq;

import cn.hutool.json.JSONUtil;
import com.mianmianshi.platform.constant.MqConstant;
import com.mianmianshi.platform.model.dto.question.QuestionSyncMessage;
import com.mianmianshi.platform.model.entity.MqSyncRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Date;
import java.util.UUID;

/**
 * 消息生产者 —— 数据变更事件捕获与消息发送
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>捕获 MySQL 数据变更事件（由 Controller/Service 触发）</li>
 *   <li>构造标准化消息（含 dataId、action、timestamp、version、priority）</li>
 *   <li>根据操作类型路由到不同队列</li>
 *   <li>写入 MqSyncRecord 记录初始状态</li>
 *   <li>通过 RabbitTemplate 发送消息</li>
 * </ol>
 *
 * <h3>顺序性保证</h3>
 * <p>对同一 questionId 的操作，通过 RabbitMQ 的单个队列天然保证顺序消费。
 * 但 SAVE 和 DELETE 走不同队列，如果短时间内对同一 ID 先 SAVE 后 DELETE，
 * 消费顺序取决于两个队列各自的消费速度。解决方案：
 * <ul>
 *   <li>SAVE 消息携带 version（updateTime），消费者判断版本号决定是否覆盖</li>
 *   <li>定时任务最终兜底，保证最终一致性</li>
 * </ul>
 * </p>
 *
 * @author mianmianshi
 */
@Slf4j
@Component
public class MqMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送题目同步消息
     *
     * @param questionId 题目 ID
     * @param action     操作类型
     * @param version    数据版本号（updateTime 毫秒值）
     * @param priority   优先级（0-10）
     */
    public void sendSyncMessage(Long questionId,
                                 QuestionSyncMessage.SyncAction action,
                                 Long version,
                                 int priority) {
        // 1. 构造消息
        QuestionSyncMessage message = QuestionSyncMessage.create(
                questionId, action, version, priority);

        // 2. 确定路由键
        String routingKey;
        if (action == QuestionSyncMessage.SyncAction.DELETE) {
            routingKey = MqConstant.QUESTION_ES_DELETE_KEY;
        } else {
            routingKey = MqConstant.QUESTION_ES_SAVE_KEY;
        }

        // 3. 关联数据 (用于 ConfirmCallback 追踪)
        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());

        // 4. 发送消息
        try {
            rabbitTemplate.convertAndSend(
                    MqConstant.QUESTION_ES_EXCHANGE,
                    routingKey,
                    message,
                    correlationData
            );
            log.info("[MQ-PRODUCER] 消息已发送: questionId={}, action={}, priority={}, "
                    + "routingKey={}, correlationId={}",
                    questionId, action, priority, routingKey, correlationData.getId());
        } catch (Exception e) {
            // 5. 发送失败——记录错误日志 + 写入数据库记录
            log.error("[MQ-PRODUCER-ERROR] 消息发送失败: questionId={}, action={}, "
                    + "routingKey={}", questionId, action, routingKey, e);
            // 发送失败意味着消息没有进入队列，后续由定时任务
            // (IncSyncQuestionToEs) 兜底同步
        }
    }

    /**
     * 发送题目新增消息（快捷方法，默认正常优先级）
     */
    public void sendSaveMessage(Long questionId, Long version) {
        sendSyncMessage(questionId, QuestionSyncMessage.SyncAction.SAVE,
                version, QuestionSyncMessage.PRIORITY_NORMAL);
    }

    /**
     * 发送题目更新消息（快捷方法，默认正常优先级）
     */
    public void sendUpdateMessage(Long questionId, Long version) {
        sendSyncMessage(questionId, QuestionSyncMessage.SyncAction.UPDATE,
                version, QuestionSyncMessage.PRIORITY_NORMAL);
    }

    /**
     * 发送题目删除消息（快捷方法，高优先级——删除需尽快生效）
     */
    public void sendDeleteMessage(Long questionId, Long version) {
        sendSyncMessage(questionId, QuestionSyncMessage.SyncAction.DELETE,
                version, QuestionSyncMessage.PRIORITY_HIGH);
    }
}
