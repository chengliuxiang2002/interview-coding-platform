-- ============================================
-- 面面是刷题平台 - 数据库初始化
-- ============================================

-- 创建库
create database if not exists mianmianshi;

-- 切换库
use mianmianshi;

-- 用户表
create table if not exists user
(
    id           bigint auto_increment comment 'id' primary key,
    userAccount  varchar(256)                           not null comment '账号',
    userPassword varchar(512)                           not null comment '密码',
    userName     varchar(256)                           null comment '用户昵称',
    userAvatar   varchar(1024)                          null comment '用户头像',
    userProfile  varchar(512)                           null comment '用户简介',
    userRole     varchar(256) default 'user'            not null comment '用户角色：user/admin/ban',
    editTime     datetime     default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime   datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime   datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint      default 0                 not null comment '是否删除'
) comment '用户' collate = utf8mb4_unicode_ci;

-- 题库表
create table if not exists question_bank
(
    id          bigint auto_increment comment 'id' primary key,
    title       varchar(256)                       null comment '标题',
    description text                               null comment '描述',
    picture     varchar(2048)                      null comment '图片',
    userId      bigint                             not null comment '创建用户 id',
    editTime    datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime  datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete    tinyint  default 0                 not null comment '是否删除',
    index idx_title (title)
) comment '题库' collate = utf8mb4_unicode_ci;

-- 题目表（增强版：支持在线编程刷题）
create table if not exists question
(
    id                bigint auto_increment comment 'id' primary key,
    title             varchar(256)                       null comment '标题',
    content           text                               null comment '内容（题目描述，支持 Markdown）',
    difficulty        varchar(50)                        null comment '难度：easy/medium/hard',
    tags              varchar(1024)                      null comment '标签列表（json 数组）',
    answer            text                               null comment '推荐答案/题解',
    supportedLanguages varchar(512)                      null comment '支持的编程语言（JSON数组）',
    codeTemplate      text                               null comment '代码模板（JSON对象，key为语言，value为模板代码）',
    testCases         text                               null comment '测试用例（JSON数组，含input和expectedOutput）',
    acceptedNum       int      default 0                 null comment '通过次数',
    submitNum         int      default 0                 null comment '提交次数',
    userId            bigint                             not null comment '创建用户 id',
    editTime          datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    createTime        datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime        datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete          tinyint  default 0                 not null comment '是否删除',
    index idx_title (title),
    index idx_userId (userId)
) comment '题目' collate = utf8mb4_unicode_ci;

-- 题库题目表（硬删除）
create table if not exists question_bank_question
(
    id             bigint auto_increment comment 'id' primary key,
    questionBankId bigint                             not null comment '题库 id',
    questionId     bigint                             not null comment '题目 id',
    userId         bigint                             not null comment '创建用户 id',
    createTime     datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime     datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    UNIQUE (questionBankId, questionId)
) comment '题库题目' collate = utf8mb4_unicode_ci;

-- 代码提交记录表
create table if not exists code_submission
(
    id              bigint auto_increment comment 'id' primary key,
    questionId      bigint                             not null comment '题目 id',
    userId          bigint                             not null comment '提交用户 id',
    language        varchar(50)                        not null comment '编程语言',
    code            text                               not null comment '提交的代码',
    status          varchar(50)                        not null comment '判题状态：PENDING/RUNNING/ACCEPTED/WRONG_ANSWER/COMPILE_ERROR/RUNTIME_ERROR/TIME_LIMIT_EXCEEDED',
    passedCases     int                                null comment '通过测试用例数',
    totalCases      int                                null comment '总测试用例数',
    executeTimeMs   bigint                             null comment '执行时间（毫秒）',
    memoryUsageKb   bigint                             null comment '执行内存（KB）',
    judgeResult     text                               null comment '判题结果详情（JSON）',
    errorMessage    text                               null comment '错误信息',
    createTime      datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime      datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete        tinyint  default 0                 not null comment '是否删除（逻辑删除）',
    index idx_questionId (questionId),
    index idx_userId (userId)
) comment '代码提交记录' collate = utf8mb4_unicode_ci;

-- MQ 同步任务执行记录表
create table if not exists mq_sync_record
(
    id               bigint auto_increment comment '主键' primary key,
    question_id      bigint                             null comment '数据 ID（题目 ID）',
    action           varchar(20)                        null comment '操作类型：SAVE/UPDATE/DELETE',
    message_id       varchar(128)                       null comment '消息 ID（RabbitMQ messageId）',
    message_body     text                               null comment '消息体 JSON 快照',
    version          bigint                             null comment '消息版本号',
    priority         int      default 5                 null comment '消息优先级',
    status           varchar(20)                        null comment '处理状态：PROCESSING/SUCCESS/FAILED/SKIPPED',
    retry_count      int      default 0                 null comment '重试次数',
    error_message    varchar(2048)                      null comment '错误信息',
    error_stack      text                               null comment '错误堆栈',
    cost_ms          bigint                             null comment '处理耗时（毫秒）',
    message_time     datetime                           null comment '消息生成时间',
    process_start_time datetime                         null comment '处理开始时间',
    process_end_time   datetime                         null comment '处理结束时间',
    create_time      datetime default CURRENT_TIMESTAMP null comment '记录创建时间',
    index idx_question_id (question_id),
    index idx_status (status),
    index idx_create_time (create_time)
) comment 'MQ 同步任务执行记录' collate = utf8mb4_unicode_ci;

-- 用户扩展字段
ALTER TABLE user
    ADD phoneNumber        VARCHAR(20) COMMENT '手机号',
    ADD email              VARCHAR(256) COMMENT '邮箱',
    ADD grade              VARCHAR(50) COMMENT '年级',
    ADD workExperience     VARCHAR(512) COMMENT '工作经验',
    ADD expertiseDirection VARCHAR(512) COMMENT '擅长方向';
