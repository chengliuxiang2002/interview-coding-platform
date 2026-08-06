package com.mianmianshi.platform.config;

import com.mianmianshi.platform.constant.MqConstant;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 *
 * <h3>架构设计</h3>
 * <pre>
 * ┌──────────────────────────────────────────────────────────┐
 * │               Exchange: question.es.sync (Topic)         │
 * │                                                          │
 * │  ──save.key──→  save.queue  ──(3次重试失败)──→ save.dead.queue  │
 * │  ──delete.key─→ delete.queue ──(3次重试失败)──→ delete.dead.queue │
 * └──────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>关键设计决策</h3>
 * <ul>
 *   <li>交换机类型：Topic — 支持按 routing key 模式匹配路由</li>
 *   <li>队列：保存和删除分开，避免互相阻塞</li>
 *   <li>持久化：所有交换机和队列 durable=true</li>
 *   <li>死信队列：重试 3 次失败后进入死信队列，防止无限重试</li>
 *   <li>消息转换器：Jackson2Json，支持复杂对象序列化</li>
 *   <li>消费端手动 ACK + QOS prefetch=10</li>
 * </ul>
 *
 * @author mianmianshi
 */
@Configuration
public class RabbitMqConfig {

    // ==================== 交换机 ====================

    @Bean
    public TopicExchange questionEsExchange() {
        return ExchangeBuilder.topicExchange(MqConstant.QUESTION_ES_EXCHANGE)
                .durable(true)
                .build();
    }

    // ==================== 业务队列 ====================

    /**
     * 保存/更新队列
     * <p>x-dead-letter-exchange 和 x-dead-letter-routing-key 指定死信投递目标</p>
     */
    @Bean
    public Queue questionEsSaveQueue() {
        return QueueBuilder.durable(MqConstant.QUESTION_ES_SAVE_QUEUE)
                .deadLetterExchange(MqConstant.QUESTION_ES_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.DEAD_KEY_PREFIX
                        + MqConstant.QUESTION_ES_SAVE_KEY)
                .build();
    }

    /**
     * 删除队列
     */
    @Bean
    public Queue questionEsDeleteQueue() {
        return QueueBuilder.durable(MqConstant.QUESTION_ES_DELETE_QUEUE)
                .deadLetterExchange(MqConstant.QUESTION_ES_EXCHANGE)
                .deadLetterRoutingKey(MqConstant.DEAD_KEY_PREFIX
                        + MqConstant.QUESTION_ES_DELETE_KEY)
                .build();
    }

    // ==================== 死信队列 ====================

    /**
     * 保存/更新死信队列
     * <p>消费 3 次仍失败的保存/更新消息最终进入此队列，需人工介入处理</p>
     */
    @Bean
    public Queue questionEsSaveDeadQueue() {
        return QueueBuilder.durable(MqConstant.QUESTION_ES_SAVE_DEAD_QUEUE)
                .build();
    }

    /**
     * 删除死信队列
     */
    @Bean
    public Queue questionEsDeleteDeadQueue() {
        return QueueBuilder.durable(MqConstant.QUESTION_ES_DELETE_DEAD_QUEUE)
                .build();
    }

    // ==================== 绑定关系 ====================

    @Bean
    public Binding saveBinding() {
        return BindingBuilder.bind(questionEsSaveQueue())
                .to(questionEsExchange())
                .with(MqConstant.QUESTION_ES_SAVE_KEY);
    }

    @Bean
    public Binding deleteBinding() {
        return BindingBuilder.bind(questionEsDeleteQueue())
                .to(questionEsExchange())
                .with(MqConstant.QUESTION_ES_DELETE_KEY);
    }

    @Bean
    public Binding saveDeadBinding() {
        return BindingBuilder.bind(questionEsSaveDeadQueue())
                .to(questionEsExchange())
                .with(MqConstant.DEAD_KEY_PREFIX
                        + MqConstant.QUESTION_ES_SAVE_KEY);
    }

    @Bean
    public Binding deleteDeadBinding() {
        return BindingBuilder.bind(questionEsDeleteDeadQueue())
                .to(questionEsExchange())
                .with(MqConstant.DEAD_KEY_PREFIX
                        + MqConstant.QUESTION_ES_DELETE_KEY);
    }

    // ==================== RabbitTemplate ====================

    /**
     * 配置 RabbitTemplate：JSON 序列化 + 发送确认回调
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jackson2MessageConverter());

        // 发送端确认回调：消息到达交换机
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack && correlationData != null) {
                // 消息未能到达交换机，记录日志供监控采集
                System.err.printf("[MQ-CONFIRM-FAIL] id=%s, cause=%s%n",
                        correlationData.getId(), cause);
            }
        });

        // 发送端退回回调：消息未能路由到队列
        template.setReturnsCallback(returned -> {
            System.err.printf("[MQ-RETURN] msg=%s, replyCode=%s, replyText=%s, " +
                            "exchange=%s, routingKey=%s%n",
                    new String(returned.getMessage().getBody()),
                    returned.getReplyCode(), returned.getReplyText(),
                    returned.getExchange(), returned.getRoutingKey());
        });

        return template;
    }

    // ==================== 监听容器工厂 ====================

    /**
     * 自定义监听容器：手动 ACK + 限流
     */
    @Bean
    public RabbitListenerContainerFactory<SimpleMessageListenerContainer>
            rabbitListenerContainerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // 手动确认模式
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        // 每次拉取最多 10 条，防止消费过快压垮下游
        factory.setPrefetchCount(10);
        // 并发消费者数量
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }

    // ==================== 消息转换器 ====================

    @Bean
    public Jackson2JsonMessageConverter jackson2MessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
