package com.mianmianshi.platform.constant;

/**
 * RabbitMQ 常量定义
 *
 * <p>定义了整个 MQ 异步同步系统的交换机、队列、路由键和死信队列配置。</p>
 *
 * <h3>架构拓扑</h3>
 * <pre>
 * Exchange: question.es.sync (Topic)
 *   ├── routing key: question.es.sync.save   → save.queue   → save.dead.queue
 *   └── routing key: question.es.sync.delete → delete.queue → delete.dead.queue
 * </pre>
 *
 * @author mianmianshi
 */
public interface MqConstant {

    // ==================== 交换机 ====================

    /** 题目 ES 同步交换机（Topic 类型） */
    String QUESTION_ES_EXCHANGE = "question.es.sync";

    // ==================== 队列 ====================

    /** 题目保存/更新队列 */
    String QUESTION_ES_SAVE_QUEUE = "question.es.sync.save.queue";

    /** 题目删除队列 */
    String QUESTION_ES_DELETE_QUEUE = "question.es.sync.delete.queue";

    /** 保存队列对应的死信队列 */
    String QUESTION_ES_SAVE_DEAD_QUEUE = "question.es.sync.save.dead.queue";

    /** 删除队列对应的死信队列 */
    String QUESTION_ES_DELETE_DEAD_QUEUE = "question.es.sync.delete.dead.queue";

    // ==================== 路由键 ====================

    /** 保存/更新路由键 */
    String QUESTION_ES_SAVE_KEY = "question.es.sync.save";

    /** 删除路由键 */
    String QUESTION_ES_DELETE_KEY = "question.es.sync.delete";

    /** 死信队列路由键前缀 */
    String DEAD_KEY_PREFIX = "dead.";

    // ==================== 系统阈值 ====================

    /** 队列积压告警阈值 */
    int ALERT_QUEUE_BACKLOG = 1000;

    /** 1 分钟内失败率告警阈值（百分比） */
    double ALERT_FAIL_RATE = 10.0;

    /** Redis 幂等性 key 过期时间（秒） */
    long IDEMPOTENT_KEY_TTL_SECONDS = 3600;

    /** 消息处理超时告警（毫秒） */
    long ALERT_PROCESS_TIMEOUT_MS = 5000;
}
