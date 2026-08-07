# 面试刷题平台 — ShardingSphere 分库分表 + Druid 慢查询分析 完整集成方案

---

## 一、项目现状分析

### 1.1 技术栈梳理

| 组件 | 当前版本 | 兼容性说明 |
|------|---------|-----------|
| Spring Boot | 2.7.2 | — |
| Java | 1.8 | 限制 ShardingSphere-JDBC 版本 ≤ 5.4.x |
| MyBatis-Plus | 3.5.2 | 与 ShardingSphere 兼容 |
| Druid | 1.2.23 | 已集成，需替换 HikariCP → DruidDataSource |
| MySQL | 8.0 (推测) | 分库分表目标数据库 |
| Redis | — | 作为缓存层与分布式锁 |
| Elasticsearch | 7.x/8.x | 题目搜索，分表后需同步 |
| RabbitMQ | 3.x | 异步消息，用于 ES 同步 |
| Sentinel | 2021.0.5.0 | 流量控制，需配合 SQL 熔断 |
| Nacos | 0.2.12 | 配置中心 |

### 1.2 数据表与增长评估

| 表名 | 当前数据量级 | 年增长率 | 分片优先级 | 分片策略 |
|------|------------|---------|-----------|---------|
| `user` | < 10 万 | 10%~20% | **不分片** | 单库单表 |
| `question` | < 10 万 | 20%~50% | 低 | 按 `id` 取模 |
| `question_bank` | < 1 万 | 10%~30% | 不分片 | 单库单表 |
| `question_bank_question` | < 50 万 | 20%~50% | 中 | 按 `questionId` 取模 |
| `code_submission` | **最高** | **100%~300%** | **高** | 按 `userId` 取模 |
| `mq_sync_record` | 中高 | 50%~100% | 中 | 按 `create_time` 范围 |

> **核心结论**：`code_submission` 表是分库分表的首要目标，`mq_sync_record` 次之。

### 1.3 已集成的 Druid 配置现状

现有 Druid 配置（[application.yml](file:///c:/Users/2644513/Desktop/校招/interview_platform/mianmianshi-backend/src/main/resources/application.yml#L25-L78)）已包含：
- 连接池基础参数（initial-size=20、minIdle=20、max-active=200、max-wait=2000）
- 慢 SQL 监控（slow-sql-millis=2000，即 2 秒）
- StatFilter、WallFilter、Log4j2Filter
- WebStatFilter + StatViewServlet

**待增强**：慢查询阈值需从 2s 调整为 500ms（见下文分析）、需增加自定义告警逻辑。

---

## 二、ShardingSphere 分库分表设计

### 2.1 版本选型

选择 **ShardingSphere-JDBC 5.4.1**：

- 5.5.x+ 需要 Java 17，当前项目为 Java 8，故锁定 5.4.x
- 5.4.1 是 5.4 分支稳定版，与 Spring Boot 2.7.x + MyBatis-Plus 3.5.x 组合经过充分验证
- 内置读写分离、分布式事务（Seata 集成）、数据加密、影子库等

### 2.2 分片方案总览

```
┌────────────────────────────────────────────────────────────────────────┐
│                       ShardingSphere 分片架构                          │
├──────────┬─────────────────┬─────────────────┬────────────────────────┤
│  数据库   │     ds0          │     ds1          │  说明                  │
├──────────┼─────────────────┼─────────────────┼────────────────────────┤
│  user    │  单表（广播表）    │  单表（广播表）    │  不分片，全库冗余        │
│  question_bank│ 单表（广播表）│  单表（广播表）    │  不分片，全库冗余        │
│  question│  question_0~1   │  question_0~1   │  2库 × 2表 = 4 分片    │
│  question_bank_question│ qbq_0~1 │  qbq_0~1   │  按 questionId 分片    │
│  code_submission│ cs_0~3  │  cs_0~3         │  2库 × 4表 = 8 分片    │
│  mq_sync_record│ mq_2025~  │  mq_2025~       │  按年分片               │
└──────────┴─────────────────┴─────────────────┴────────────────────────┘
```

### 2.3 分片键选择

| 表名 | 分片键 | 选择理由 |
|------|--------|---------|
| `code_submission` | `user_id` | 查询场景 90% 按用户维度，`user_id` 能保证同一用户数据落在同一分片，避免跨库 JOIN |
| `question` | `id` | Snowflake ID 天然均匀分布，`id % N` 实现负载均衡 |
| `question_bank_question` | `question_id` | 保证与 `question` 相同分片路由，避免跨库查询 |
| `mq_sync_record` | `create_time` | 日志型数据，天然按时间分区，历史数据归档后可直接 truncate 旧分片 |

### 2.4 分片算法实现

```java
package com.mianmianshi.platform.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 基于 userId 取模的精确分片算法
 * 用于 code_submission 表
 */
public class UserIdModShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    private static final String ALGORITHM_TYPE = "USER_ID_MOD";

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Long> shardingValue) {
        // 2 库 × 4 表：先确定库，再确定表
        long userId = shardingValue.getValue();
        int dbCount = 2;
        int tableCount = 4;

        // ds0 或 ds1
        String dbSuffix = String.valueOf(userId % dbCount);
        // code_submission_0 ~ code_submission_3
        String tableSuffix = String.valueOf(userId % tableCount);

        String logicTable = shardingValue.getLogicTableName();
        // 返回格式: ds0.code_submission_2
        for (String name : availableTargetNames) {
            if (name.startsWith("ds" + dbSuffix) && name.contains(logicTable + "_" + tableSuffix)) {
                return name;
            }
        }
        throw new IllegalArgumentException("No available target for userId=" + userId);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                          RangeShardingValue<Long> shardingValue) {
        // 范围查询：广播到所有分片
        return availableTargetNames;
    }

    @Override
    public Properties getProps() { return new Properties(); }

    @Override
    public String getType() { return ALGORITHM_TYPE; }

    @Override
    public void init(Properties props) {}
}
```

```java
package com.mianmianshi.platform.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于年份的精确分片算法
 * 用于 mq_sync_record 表
 */
public class YearBasedShardingAlgorithm implements StandardShardingAlgorithm<Date> {

    private static final String ALGORITHM_TYPE = "YEAR_BASED";
    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");

    @Override
    public String doSharding(Collection<String> availableTargetNames,
                             PreciseShardingValue<Date> shardingValue) {
        LocalDateTime dt = shardingValue.getValue().toInstant()
                .atZone(ZoneId.systemDefault()).toLocalDateTime();
        String year = dt.format(YEAR_FMT);
        String tableName = shardingValue.getLogicTableName() + "_" + year;
        for (String name : availableTargetNames) {
            if (name.contains(tableName)) return name;
        }
        throw new IllegalArgumentException("No table for year " + year);
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                          RangeShardingValue<Date> shardingValue) {
        // 范围查询：返回范围内涉及的所有年份表
        Range<Date> range = shardingValue.getValueRange();
        String prefix = shardingValue.getLogicTableName() + "_";
        return availableTargetNames.stream()
                .filter(n -> n.contains(prefix))
                .collect(Collectors.toList());
    }

    @Override public Properties getProps() { return new Properties(); }
    @Override public String getType() { return ALGORITHM_TYPE; }
    @Override public void init(Properties props) {}
}
```

### 2.5 ShardingSphere 配置

```yaml
# application-sharding.yml — 分库分表配置（通过 Nacos 动态下发）

spring:
  autoconfigure:
    exclude: com.alibaba.druid.spring.boot.autoconfigure.DruidDataSourceAutoConfigure

  shardingsphere:
    # 数据源定义
    datasource:
      names: ds0,ds1
      ds0:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://10.0.0.1:3306/mianmianshi_db0?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
        username: root
        password: ${DB_PASSWORD_0}
      ds1:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://10.0.0.2:3306/mianmianshi_db1?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
        username: root
        password: ${DB_PASSWORD_1}

    # 分片规则
    rules:
      sharding:
        # ============ 默认分库策略 ============
        defaultDatabaseStrategy:
          none:

        # ============ 广播表（不分片，每个库都有完整数据） ============
        broadcastTables:
          - user
          - question_bank

        # ============ 分片表规则 ============
        tables:

          # --- question 表 ---
          question:
            actualDataNodes: ds$->{0..1}.question_$->{0..1}
            databaseStrategy:
              standard:
                shardingColumn: id
                shardingAlgorithmName: question_db_inline
            tableStrategy:
              standard:
                shardingColumn: id
                shardingAlgorithmName: question_tbl_inline

          # --- question_bank_question 表 ---
          question_bank_question:
            actualDataNodes: ds$->{0..1}.question_bank_question_$->{0..1}
            databaseStrategy:
              standard:
                shardingColumn: question_id
                shardingAlgorithmName: qbq_db_inline
            tableStrategy:
              standard:
                shardingColumn: question_id
                shardingAlgorithmName: qbq_tbl_inline

          # --- code_submission 表 ---
          code_submission:
            actualDataNodes: ds$->{0..1}.code_submission_$->{0..3}
            databaseStrategy:
              standard:
                shardingColumn: user_id
                shardingAlgorithmName: cs_db_mod
            tableStrategy:
              standard:
                shardingColumn: user_id
                shardingAlgorithmName: cs_tbl_mod

          # --- mq_sync_record 表 ---
          mq_sync_record:
            actualDataNodes: ds$->{0..1}.mq_sync_record_$->{2025..2030}
            databaseStrategy:
              standard:
                shardingColumn: create_time
                shardingAlgorithmName: mq_db_year
            tableStrategy:
              standard:
                shardingColumn: create_time
                shardingAlgorithmName: mq_tbl_year

        # ============ 分片算法定义 ============
        shardingAlgorithms:

          # question: INLINE 表达式（id % 2）
          question_db_inline:
            type: INLINE
            props:
              algorithm-expression: ds$->{id % 2}
          question_tbl_inline:
            type: INLINE
            props:
              algorithm-expression: question_$->{id % 2}

          # question_bank_question: INLINE 表达式
          qbq_db_inline:
            type: INLINE
            props:
              algorithm-expression: ds$->{question_id % 2}
          qbq_tbl_inline:
            type: INLINE
            props:
              algorithm-expression: question_bank_question_$->{question_id % 2}

          # code_submission: 自定义算法类
          cs_db_mod:
            type: USER_ID_MOD
          cs_tbl_mod:
            type: USER_ID_MOD

          # mq_sync_record: 按年分片
          mq_db_year:
            type: YEAR_BASED
          mq_tbl_year:
            type: YEAR_BASED

        # ============ 主键生成 ============
        keyGenerators:
          snowflake:
            type: SNOWFLAKE

    # ============ 属性配置 ============
    props:
      sql-show: false                                    # 生产关闭 SQL 打印
      sql-simple: true                                    # 简单日志格式
      max-connections-size-per-query: 1                    # 单次查询最大连接数
      kernel-executor-size: 32                             # 执行线程池大小
      check-table-metadata-enabled: true                   # 启动时检查元数据
```

> **注意**：上述配置使用 YAML 格式，ShardingSphere 5.4.x 同时支持 YAML 和 Java API 配置方式，在 Spring Boot 环境中推荐 YAML。

### 2.6 读写分离配置

```yaml
# 在 rules 节点下增加读写分离配置
rules:
  # ... 分片规则 ...
  readwrite-splitting:
    dataSources:
      ds0_rw:
        type: Static
        props:
          write-data-source-name: ds0
          read-data-source-names: ds0_slave_01,ds0_slave_02
        load-balancer-name: round_robin
      ds1_rw:
        type: Static
        props:
          write-data-source-name: ds1
          read-data-source-names: ds1_slave_01,ds1_slave_02
        load-balancer-name: round_robin
    loadBalancers:
      round_robin:
        type: ROUND_ROBIN
```

**读写分离路由策略**：

- 所有写操作 + 事务内操作 → 强制走主库
- 查询操作 → 轮询从库
- 可通过 Hint 强制走主库：`HintManager.getInstance().setWriteRouteOnly()`

### 2.7 分布式事务处理

采用 **Seata AT 模式**（项目已使用 Nacos，天然配套）：

```
┌─────────┐     ┌──────────┐     ┌───────────────┐
│ 业务服务  │────▶│ Seata TC │────▶│ ds0 (主库)     │
│ (TM)    │     │ (Nacos)  │     │ ds1 (主库)     │
└─────────┘     └──────────┘     │ Redis (全局锁)  │
                                 └───────────────┘
```

```yaml
# Seata 配置（已集成 Nacos）
seata:
  enabled: true
  application-id: mianmianshi-backend
  tx-service-group: mianmianshi-tx-group
  config:
    type: nacos
    nacos:
      server-addr: 127.0.0.1:8848
      group: SEATA_GROUP
      namespace: ""
  registry:
    type: nacos
    nacos:
      application: seata-server
      server-addr: 127.0.0.1:8848
      group: SEATA_GROUP
```

**事务使用示例**：

```java
// 使用全局事务注解
@GlobalTransactional(name = "create_submission", timeoutMills = 300000)
public void createCodeSubmission(CodeSubmission submission) {
    // 写入 code_submission → 路由到 ds0.code_submission_2
    codeSubmissionMapper.insert(submission);
    // 更新 question.acceptedNum → 路由到 ds1.question_1
    questionMapper.updateAcceptedNum(submission.getQuestionId());
}
```

**柔性事务兜底方案**：

对于非强一致性场景（如代码提交统计），可采用：
- **本地消息表 + RabbitMQ**：保证最终一致性（项目已有 MQ 基础）
- **Redis 分布式锁**：对关键操作（如扣减/累加）做乐观锁/悲观锁

### 2.8 分库分表后的 SQL 兼容性处理

#### 规避问题清单

| 问题类型 | 说明 | 处理方案 |
|---------|------|---------|
| 跨库 JOIN | 不允许 | 拆分成多次查询 + 代码层聚合 |
| 不带分片键查询 | 全库广播，性能差 | 强制 WHERE 带分片键；全局表场景允许 |
| 聚合函数（SUM/COUNT） | 需要归并 | ShardingSphere 自动归并，注意精度 |
| 子查询 | 部分支持 | 避免深层嵌套子查询 |
| DISTINCT / GROUP BY | 需要归并正确 | 尽量在分片内完成聚合 |
| 自增 ID | 分片后不可用 | 全表使用 SNOWFLAKE 生成 |
| LIMIT 分页 | 全分片获取后归并 | 改用游标分页或 ES 搜索 |

#### 代码改造示例

```java
// 改造前：直接按非分片键查询（全库广播，❌ 不可接受）
codeSubmissionMapper.selectList(
    new LambdaQueryWrapper<CodeSubmission>()
        .eq(CodeSubmission::getStatus, "ACCEPTED")
);

// 改造后：必须带分片键
codeSubmissionMapper.selectList(
    new LambdaQueryWrapper<CodeSubmission>()
        .eq(CodeSubmission::getUserId, currentUserId)  // 分片键
        .eq(CodeSubmission::getStatus, "ACCEPTED")
);

// 全局统计场景：走 Elasticsearch
elasticsearchRestTemplate.search(query, CodeSubmission.class);
```

#### 分页查询改造

```java
// 改造前：MyBatis-Plus 分页（数据量小时无问题）
Page<CodeSubmission> page = new Page<>(pageNum, pageSize);
codeSubmissionMapper.selectPage(page, wrapper);

// 改造后：超大分页 → 游标分页
// 方案1：基于 cursor（id 或时间戳）
List<CodeSubmission> list = codeSubmissionMapper.selectByCursor(
    userId, lastId, pageSize  // 带上分片键
);

// 方案2：走 Elasticsearch（项目已有 ES 集成）
Page<CodeSubmission> page = codeSubmissionService.searchFromES(
    userId, pageNum, pageSize
);
```

---

## 三、数据迁移方案

### 3.1 整体策略

```
┌──────────────────────────────────────────────────────────────────┐
│                     三阶段数据迁移策略                              │
├──────────┬──────────────────┬────────────────────────────────────┤
│ 阶段一    │ 全量历史数据迁移    │ 停机窗口，4-6 小时                   │
│ 阶段二    │ 增量数据同步       │ 双写 + 校验，灰度过程中持续运行          │
│ 阶段三    │ 数据校验与切换     │ 逐表验证 + 切换流量                   │
└──────────┴──────────────────┴────────────────────────────────────┘
```

### 3.2 全量迁移脚本

```java
package com.mianmianshi.platform.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.*;

/**
 * 全量数据迁移工具
 * 在停机窗口期间执行，将旧库数据按分片规则写入新库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullDataMigrator {

    private final DataSource oldDataSource;
    private final ShardingSphereDataSource shardingDataSource;

    private final ExecutorService executor = new ThreadPoolExecutor(
            8, 16, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000)
    );

    /**
     * 迁移 code_submission 表
     */
    public MigrationResult migrateCodeSubmission(int batchSize) {
        JdbcTemplate oldJdbc = new JdbcTemplate(oldDataSource);
        long total = oldJdbc.queryForObject(
            "SELECT COUNT(*) FROM code_submission WHERE isDelete = 0", Long.class);
        log.info("code_submission 待迁移: {} 条", total);

        MigrationResult result = new MigrationResult("code_submission");

        long lastId = 0;
        while (true) {
            // 批量读取（基于游标，避免深分页）
            List<Map<String, Object>> rows = oldJdbc.queryForList(
                "SELECT * FROM code_submission WHERE id > ? AND isDelete = 0 " +
                "ORDER BY id ASC LIMIT ?", lastId, batchSize);

            if (rows.isEmpty()) break;

            // 并发写入分片库
            List<Future<Void>> futures = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                futures.add(executor.submit(() -> {
                    long userId = ((Number) row.get("userId")).longValue();
                    // 通过 userId 路由到正确的分片
                    String insertSql = "INSERT INTO code_submission " +
                        "(id, questionId, userId, language, code, status, " +
                        " passedCases, totalCases, executeTimeMs, memoryUsageKb, " +
                        " judgeResult, errorMessage, createTime, updateTime, isDelete) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

                    // ShardingSphere 会根据 userId 自动路由
                    new JdbcTemplate(shardingDataSource).update(insertSql,
                        row.get("id"), row.get("questionId"), row.get("userId"),
                        row.get("language"), row.get("code"), row.get("status"),
                        row.get("passedCases"), row.get("totalCases"),
                        row.get("executeTimeMs"), row.get("memoryUsageKb"),
                        row.get("judgeResult"), row.get("errorMessage"),
                        row.get("createTime"), row.get("updateTime"), row.get("isDelete"));
                    return null;
                }));
            }

            // 等待当前批次完成
            for (Future<Void> f : futures) {
                try { f.get(); } catch (Exception e) { result.addError(e); }
            }

            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
            result.addSuccess(rows.size());
            log.info("进度: {}/{} ({:.1f}%)", result.getSuccessCount(), total,
                result.getSuccessCount() * 100.0 / total);
        }

        executor.shutdown();
        return result;
    }
}
```

### 3.3 增量数据同步（双写方案）

```java
package com.mianmianshi.platform.migration;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

/**
 * 双写切面：在灰度期间同时写入旧库和新库（分片库）
 */
@Aspect
@Component
@Slf4j
public class DualWriteAspect {

    private final DataSource oldDataSource;
    private final DataSource shardingDataSource;

    /**
     * 拦截 Mapper 的写操作，执行双写
     */
    @Around("execution(* com.mianmianshi.platform.mapper.*.insert*(..)) " +
            "|| execution(* com.mianmianshi.platform.mapper.*.update*(..))")
    public Object dualWrite(ProceedingJoinPoint pjp) throws Throwable {
        // 1. 先写分片库（新）
        Object result = pjp.proceed();

        // 2. 同步写旧库（需要根据原对象构建旧库 SQL）
        // 此处简化，实际需解析参数并构建原生 JDBC 写入
        writeToOldDB(pjp.getArgs());

        return result;
    }

    private void writeToOldDB(Object[] args) {
        // 实现：根据不同的实体类型，构建旧库的 INSERT/UPDATE SQL
        // ...
    }
}
```

### 3.4 数据校验

```java
/**
 * 数据一致性校验器：对比旧库和新库的数据
 */
@Component
public class DataConsistencyVerifier {

    public VerifyResult verify(String tableName, long startId, long endId, int batchSize) {
        VerifyResult result = new VerifyResult(tableName);
        long currentId = startId;

        while (currentId <= endId) {
            // 从旧库读一批
            List<Map<String, Object>> oldRows = readFromOld(tableName, currentId, batchSize);
            // 从分片库读一批
            List<Map<String, Object>> newRows = readFromSharding(tableName, currentId, batchSize);

            // MD5 对比
            for (int i = 0; i < oldRows.size(); i++) {
                String oldMd5 = md5(oldRows.get(i));
                String newMd5 = md5(newRows.get(i));
                if (!oldMd5.equals(newMd5)) {
                    result.addMismatch(idOf(oldRows.get(i)), oldMd5, newMd5);
                }
            }
            currentId += batchSize;
        }
        return result;
    }
}
```

---

## 四、Druid 监控与慢查询分析实现

### 4.1 Druid 增强配置

在现有 [application.yml](file:///c:/Users/2644513/Desktop/校招/interview_platform/mianmianshi-backend/src/main/resources/application.yml) 基础上修改如下：

```yaml
spring:
  datasource:
    # 当接入 ShardingSphere 后，数据源由 ShardingSphere 管理，
    # 需在 shardingsphere.datasource 节点下配置 Druid
    druid:
      # ========= 连接池参数优化 =========
      initial-size: 10              # 从 20 调低，避免分库后连接数爆炸
      min-idle: 10
      max-active: 100               # 从 200 调低（2库→每库100=总200）
      max-wait: 2000
      time-between-eviction-runs-millis: 60000   # 从 2000 调至 60s，减少开销
      min-evictable-idle-time-millis: 300000     # 5 分钟
      max-evictable-idle-time-millis: 600000     # 10 分钟
      validation-query: SELECT 1
      test-while-idle: true
      test-on-borrow: false
      test-on-return: false

      # 分库分表场景：连接预热
      keep-alive: true
      keep-alive-between-time-millis: 120000    # 每 2 分钟保活

      # PSCache（分库场景建议关闭或设小值）
      pool-prepared-statements: false

      # ========= 监控统计配置 =========
      filters: stat,wall,slf4j

      filter:
        stat:
          enabled: true
          db-type: mysql
          log-slow-sql: true
          slow-sql-millis: 500                    # 关键：慢查询阈值从 2000ms → 500ms
          merge-sql: true                         # 合并同类 SQL 统计
          connection-stack-trace-enable: true     # 记录连接获取堆栈（排查连接泄漏）
        wall:
          enabled: true
          db-type: mysql
          config:
            select-all-column-allow: false        # 禁止 SELECT *
            delete-where-none-check: true         # DELETE 必须有 WHERE
            update-where-none-check: true         # UPDATE 必须有 WHERE
        slf4j:
          enabled: true
          statement-log-enabled: true
          statement-sql-format-option:
            uppercase: true
            pretty-format: true
          statement-executable-sql-log-enable: true

      # ========= Web 监控（分库场景需多数据源注册） =========
      web-stat-filter:
        enabled: true
        url-pattern: /*
        exclusions: "*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*"
        profile-enable: true                      # 单请求 SQL 分析

      stat-view-servlet:
        enabled: true
        url-pattern: /druid/*
        reset-enable: false
        login-username: ${DRUID_USERNAME:admin}
        login-password: ${DRUID_PASSWORD:admin123}
        allow: 127.0.0.1,10.0.0.0/8              # 限制内网访问
```

### 4.2 多数据源 Druid 监控注册

接入 ShardingSphere 后，每个物理数据源需单独注册 Druid 监控：

```java
package com.mianmianshi.platform.config;

import com.alibaba.druid.support.http.StatViewServlet;
import com.alibaba.druid.support.http.WebStatFilter;
import com.alibaba.druid.support.spring.stat.DruidStatInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.aop.support.JdkRegexpMethodPointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * Druid 多数据源监控配置
 * 接入 ShardingSphere 后，需显式注册监控组件
 */
@Configuration
public class DruidMonitorConfig {

    /**
     * Spring 方法级监控（AOP）
     * 对 DAO/Service 层执行时间进行采集
     */
    @Bean
    public DruidStatInterceptor druidStatInterceptor() {
        return new DruidStatInterceptor();
    }

    @Bean
    public DefaultPointcutAdvisor druidStatAdvisor(DruidStatInterceptor interceptor) {
        JdkRegexpMethodPointcut pointcut = new JdkRegexpMethodPointcut();
        pointcut.setPatterns(
            "com.mianmianshi.platform.mapper.*",
            "com.mianmianshi.platform.service.*"
        );
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor();
        advisor.setPointcut(pointcut);
        advisor.setAdvice(interceptor);
        return advisor;
    }

    /**
     * 注册 StatViewServlet（多数据源统一页面）
     */
    @Bean
    public ServletRegistrationBean<StatViewServlet> druidStatViewServlet() {
        ServletRegistrationBean<StatViewServlet> bean =
            new ServletRegistrationBean<>(new StatViewServlet(), "/druid/*");
        bean.addInitParameter("loginUsername", "admin");
        bean.addInitParameter("loginPassword", "admin123");
        bean.addInitParameter("resetEnable", "false");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<WebStatFilter> druidWebStatFilter() {
        FilterRegistrationBean<WebStatFilter> bean =
            new FilterRegistrationBean<>(new WebStatFilter());
        bean.addUrlPatterns("/*");
        bean.addInitParameter("exclusions", "*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*");
        bean.addInitParameter("profileEnable", "true");
        return bean;
    }
}
```

### 4.3 慢查询日志收集与存储

```java
package com.mianmianshi.platform.monitor;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.stat.DruidStatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.*;

/**
 * 慢查询日志收集器
 * 从 Druid StatFilter 中定时拉取慢 SQL 数据，存储到 ES
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlowSqlCollector {

    private final RabbitTemplate rabbitTemplate;
    private final SlowSqlRepository slowSqlRepository;  // ES 存储

    private final DruidStatService druidStatService = DruidStatService.getInstance();

    /**
     * 每 30 秒拉取一次慢 SQL 数据
     */
    @Scheduled(fixedDelay = 30_000)
    public void collectSlowSql() {
        try {
            // 获取所有数据源的 SQL 统计
            String result = druidStatService.service("/sql.json");

            List<SlowSqlRecord> records = parseSlowSqlFromJson(result);

            for (SlowSqlRecord record : records) {
                // 过滤非慢查询（Druid 的 slow-sql-millis 已做过滤，此处二次确认）
                if (record.getMaxTimespan() >= 500) {
                    // 存入 Elasticsearch（用于后续分析检索）
                    slowSqlRepository.save(record);

                    // 超慢 SQL（> 3s）发送 RabbitMQ 告警消息
                    if (record.getMaxTimespan() >= 3000) {
                        rabbitTemplate.convertAndSend(
                            "slow.sql.alert", record
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.error("收集慢 SQL 失败", e);
        }
    }

    private List<SlowSqlRecord> parseSlowSqlFromJson(String json) {
        // 解析 DruidStatService 返回的 JSON
        // 提取 SQL、执行次数、最慢耗时、平均耗时等
        // ...
        return Collections.emptyList();
    }
}

@Data
@Document(indexName = "slow_sql_log")
public class SlowSqlRecord {
    @Id
    private String id;

    private String dataSource;       // 数据源名称
    private String sql;               // SQL 语句（参数化后）
    private long executeCount;        // 执行次数
    private long maxTimespan;         // 最慢耗时（毫秒）
    private double avgTimespan;       // 平均耗时（毫秒）
    private long errorCount;          // 错误次数
    private Date firstTime;           // 首次出现时间
    private Date lastTime;            // 最近出现时间
    private String stackTrace;        // 调用堆栈
}
```

### 4.4 慢查询分析报告生成

```java
package com.mianmianshi.platform.monitor;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 慢查询分析报告服务
 */
@Service
@RequiredArgsConstructor
public class SlowSqlAnalyzer {

    private final SlowSqlRepository repository;

    /**
     * 生成日报
     */
    public SlowSqlDailyReport generateDailyReport() {
        LocalDateTime start = LocalDateTime.now().minus(1, ChronoUnit.DAYS);
        LocalDateTime end = LocalDateTime.now();

        List<SlowSqlRecord> records = repository.findByTimeRange(start, end);

        SlowSqlDailyReport report = new SlowSqlDailyReport();

        // === 总体统计 ===
        report.setTotalSlowSqlCount(records.size());
        report.setTotalExecutionCount(
            records.stream().mapToLong(SlowSqlRecord::getExecuteCount).sum());

        // === 慢 SQL TOP 10（按执行次数） ===
        report.setTop10ByCount(
            records.stream()
                .sorted(Comparator.comparingLong(SlowSqlRecord::getExecuteCount).reversed())
                .limit(10)
                .map(r -> new SlowSqlSummary(r.getSql(), r.getExecuteCount(),
                    r.getMaxTimespan(), r.getAvgTimespan()))
                .toList());

        // === 慢 SQL TOP 10（按最慢耗时） ===
        report.setTop10ByMaxTime(
            records.stream()
                .sorted(Comparator.comparingLong(SlowSqlRecord::getMaxTimespan).reversed())
                .limit(10)
                .map(r -> new SlowSqlSummary(r.getSql(), r.getExecuteCount(),
                    r.getMaxTimespan(), r.getAvgTimespan()))
                .toList());

        // === 分数据源统计 ===
        Map<String, Long> byDataSource = new HashMap<>();
        for (SlowSqlRecord r : records) {
            byDataSource.merge(r.getDataSource(), r.getExecuteCount(), Long::sum);
        }
        report.setDataSourceDistribution(byDataSource);

        // === 生成优化建议 ===
        report.setOptimizationSuggestions(generateSuggestions(records));

        return report;
    }

    private List<String> generateSuggestions(List<SlowSqlRecord> records) {
        List<String> suggestions = new ArrayList<>();
        for (SlowSqlRecord r : records) {
            String sql = r.getSql().toUpperCase();
            if (sql.contains("SELECT *")) {
                suggestions.add("避免使用 SELECT *，建议明确指定字段: " + r.getSql());
            }
            if (sql.contains("LIKE '%") && r.getAvgTimespan() > 1000) {
                suggestions.add("前缀模糊查询导致全表扫描，建议使用 ES 全文检索: " + r.getSql());
            }
            if (r.getAvgTimespan() > 500 && !sql.contains("WHERE")) {
                suggestions.add("SQL 未带 WHERE 条件，存在全表扫描风险: " + r.getSql());
            }
        }
        return suggestions;
    }
}

@Data
class SlowSqlDailyReport {
    private long totalSlowSqlCount;
    private long totalExecutionCount;
    private List<SlowSqlSummary> top10ByCount;
    private List<SlowSqlSummary> top10ByMaxTime;
    private Map<String, Long> dataSourceDistribution;
    private List<String> optimizationSuggestions;
}

record SlowSqlSummary(String sql, long executeCount, long maxTimespan, double avgTimespan) {}
```

### 4.5 慢查询告警策略

```java
package com.mianmianshi.platform.monitor.alarm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 慢查询告警引擎 — 基于滑动窗口
 */
@Slf4j
@Component
public class SlowSqlAlertEngine {

    // 滑动窗口：1 分钟内的慢 SQL 数量
    private final ConcurrentLinkedQueue<Long> window = new ConcurrentLinkedQueue<>();
    private final AtomicLong currentMinuteCount = new AtomicLong(0);

    /**
     * 告警规则：
     *   1. 单条 SQL 执行超过 3 秒 → 立即告警
     *   2. 1 分钟内慢 SQL 超过 100 条 → 聚合告警
     *   3. 单个数据源连接池使用率超过 80% → 连接池告警
     */
    public AlarmLevel evaluate(SlowSqlRecord record) {
        long now = System.currentTimeMillis();

        // 规则 1: 单条超时
        if (record.getMaxTimespan() >= 3000) {
            return AlarmLevel.CRITICAL;  // 严重
        }

        // 规则 2: 滑动窗口频率超限
        window.add(now);
        // 清理 1 分钟之前的数据
        while (!window.isEmpty() && now - window.peek() > 60_000) {
            window.poll();
        }
        if (window.size() > 100) {
            return AlarmLevel.WARNING;   // 警告
        }

        // 规则 3: 连续错误
        if (record.getErrorCount() > 10) {
            return AlarmLevel.WARNING;
        }

        return AlarmLevel.INFO;          // 仅记录
    }
}

enum AlarmLevel {
    INFO,       // 仅记录日志
    WARNING,    // 邮件通知
    CRITICAL    // 短信/电话 + 自动熔断
}
```

### 4.6 告警通知实现

```java
package com.mianmianshi.platform.monitor.alarm;

import org.springframework.amqp.rabbit.annotation.*;
import org.springframework.stereotype.Component;

/**
 * 告警消息处理器
 * 消费 RabbitMQ 中的慢 SQL 告警消息，分发到不同通知渠道
 */
@Component
public class AlarmNotificationHandler {

    /**
     * 消费严重告警 → 短信/企业微信通知 + Sentinel 触发降级
     */
    @RabbitListener(queues = "slow.sql.alert.critical")
    public void handleCritical(SlowSqlRecord record) {
        // 1. 发送企业微信通知
        sendWeChatNotification(record);

        // 2. 触发 Sentinel 降级（慢 SQL 过多的接口自动熔断）
        degradeIfNeeded(record);
    }

    @RabbitListener(queues = "slow.sql.alert.warning")
    public void handleWarning(SlowSqlRecord record) {
        // 仅发送邮件通知
        sendEmailNotification(record);
    }

    private void degradeIfNeeded(SlowSqlRecord record) {
        // 对产生慢 SQL 的接口进行 Sentinel 降级
        // 在 Sentinel 控制台配置对应资源的降级规则
        log.warn("触发自动降级: SQL={}, 接口={}",
            record.getSql(), record.getStackTrace());
    }
}
```

---

## 五、整体架构设计

### 5.1 组件交互流程

```
                           ┌──────────────────────┐
                           │      Nacos           │
                           │  配置中心 / 注册中心    │
                           └──────┬───────────────┘
                                  │ 动态配置下发
                                  ▼
┌──────────────┐    ┌─────────────────────────┐    ┌────────────────────┐
│  前端请求     │───▶│   Spring Boot Controller │───▶│   Sa-Token 鉴权    │
└──────────────┘    └────────────┬────────────┘    └────────────────────┘
                                  │
                         ┌────────▼─────────┐
                         │  Sentinel 流控    │
                         │  熔断降级         │
                         └────────┬─────────┘
                                  │
                         ┌────────▼─────────┐
                         │   Service 层      │
                         │   业务逻辑处理     │
                         └────────┬─────────┘
                                  │
              ┌───────────────────┼───────────────────┐
              │                   │                   │
     ┌────────▼────────┐ ┌───────▼────────┐ ┌───────▼────────┐
     │  Redisson (缓存) │ │ RabbitMQ (异步)│ │ Seata (事务)   │
     └─────────────────┘ └───────┬────────┘ └────────────────┘
                                 │
              ┌──────────────────┼──────────────────┐
              ▼                  ▼                  ▼
     ┌────────────┐    ┌────────────┐     ┌────────────────┐
     │ Elasticsearch│   │  告警通知   │     │  数据同步消费者  │
     │  搜索/日志   │    └────────────┘     └────────────────┘
     └────────────┘
                                    │
                          ┌─────────▼─────────┐
                          │  ShardingSphere   │
                          │  JDBC 分片引擎     │
                          └─────────┬─────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
            ┌───────────┐   ┌───────────┐   ┌───────────┐
            │ Druid 连接池│  │ Druid 连接池│  │ Druid 连接池│
            │  ds0 + 从库 │   │  ds1 + 从库 │   │  dsX + 从库 │
            └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
                  │               │               │
         ┌────────▼───┐   ┌───────▼───┐   ┌───────▼────────┐
         │ 慢 SQL 收集  │   │ 连接池监控  │   │ StatFilter 统计 │
         └─────┬──────┘   └─────┬─────┘   └───────┬────────┘
               │                │                 │
               └────────────────┼─────────────────┘
                                ▼
                    ┌─────────────────────┐
                    │  慢 SQL ES 存储      │
                    │  + 日/周报告         │
                    └─────────────────────┘
```

### 5.2 ShardingSphere 与 Druid 协同工作机制

```
请求到达
   │
   ▼
ShardingSphere-JDBC (SQL 解析 → 路由 → 改写 → 执行 → 归并)
   │
   ├── SQL 解析：ANTLR 解析 SQL，提取表名、分片键值
   ├── SQL 路由：根据分片键计算目标数据源 + 表
   ├── SQL 改写：将逻辑 SQL 改写为物理 SQL（逻辑表→物理表）
   ├── SQL 执行：通过 Druid 连接池获取连接 → 执行物理 SQL
   │        │
   │        ▼
   │   ┌──────────────────────────────┐
   │   │ Druid DataSource              │
   │   │ ├── StatFilter 记录执行耗时     │
   │   │ ├── WallFilter SQL 防火墙检查   │
   │   │ ├── Slf4jFilter 日志记录       │
   │   │ └── 慢 SQL 识别（> 500ms）     │
   │   └──────────────────────────────┘
   │
   └── 结果归并：汇总多个分片的结果
```

**关键协同点**：
1. **连接池管理**：Druid 在每个物理数据源（ds0/ds1）级别管理连接池，ShardingSphere 负责路由选择
2. **统计监控**：Druid StatFilter 记录的是实际执行的物理 SQL，比逻辑 SQL 更真实反映单分片性能
3. **告警联动**：Druid 检测到慢 SQL → RabbitMQ 发送告警 → 应用层做限流降级（Sentinel）

### 5.3 性能优化策略

#### 5.3.1 缓存体系

```
┌─────────────────────────────────────────────────────┐
│                    多层缓存架构                        │
├──────────┬──────────────────┬───────────────────────┤
│ L1 缓存   │ Caffeine (本地)   │ 热点数据（高频访问用户/题目）│
│ L2 缓存   │ Redis            │ 通用缓存，TTL 30min       │
│ L3 缓存   │ Elasticsearch     │ 全文搜索 + 分页替代        │
│ 降级缓冲   │ HotKey           │ 热点探测 + Sentinel 限流   │
└──────────┴──────────────────┴───────────────────────┘
```

```java
@Service
public class QuestionCacheService {

    // 本地缓存热点题目（项目已有 HotKey）
    private final Cache<Long, Question> localCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    // Redis 二级缓存
    private final RedissonClient redisson;

    public Question getQuestion(Long id) {
        // L1: 本地缓存
        Question q = localCache.getIfPresent(id);
        if (q != null) return q;

        // L2: Redis
        String cacheKey = "question:" + id;
        RBucket<Question> bucket = redisson.getBucket(cacheKey);
        q = bucket.get();
        if (q != null) {
            localCache.put(id, q);
            return q;
        }

        // L3: 数据库（ShardingSphere 路由 + Druid 执行）
        q = questionMapper.selectById(id);
        if (q != null) {
            bucket.set(q, 30, TimeUnit.MINUTES);
            localCache.put(id, q);
        }
        return q;
    }
}
```

#### 5.3.2 索引设计

分库分表后，每张物理表的索引需要重新规划：

```sql
-- code_submission 分片表索引
-- (已经在分片表的每个物理表中执行，例如 ds0.code_submission_0)

-- 核心查询：按 user_id + create_time 倒序（分片键 + 时间排序）
ALTER TABLE code_submission
    ADD INDEX idx_userId_createTime (userId, createTime DESC);

-- 按题目统计（非分片键查询 — 跨分片，性能一般但可接受）
ALTER TABLE code_submission
    ADD INDEX idx_questionId_status (questionId, status);

-- question 分片表索引
ALTER TABLE question
    ADD INDEX idx_userId (userId);
ALTER TABLE question
    ADD INDEX idx_difficulty (difficulty);

-- 全局唯一索引需慎用（跨分片无法保证全局唯一）
-- 在 app 层用 Redis SET NX 实现业务唯一性
```

#### 5.3.3 SQL 优化建议

| 场景 | 问题 SQL | 优化后 |
|------|---------|--------|
| 全库扫描 | `SELECT * FROM question` | 必须带 `WHERE id = ?` 或走 ES 搜索 |
| 深分页 | `LIMIT 10000, 20` | 游标分页：`WHERE id > ? ORDER BY id LIMIT 20` |
| 大字段查询 | `SELECT * FROM code_submission` | 仅查必要字段；分两步：先查列表再查详情 |
| 统计查询 | `SELECT COUNT(*) FROM code_submission` | 异步写入 Redis 计数器；ES 聚合 |
| 模糊搜索 | `LIKE '%keyword%'` | 使用 Elasticsearch 全文搜索 |

### 5.4 高可用保障方案

#### 5.4.1 故障自动切换

| 故障类型 | 检测方式 | 切换策略 | 恢复策略 |
|---------|---------|---------|---------|
| 主库宕机 | MySQL MHA / Orchestrator | 自动提升从库为主库 | 原主库恢复后变为从库 |
| 从库宕机 | Druid 连接测试 + 心跳 | 读写分离自动摘除故障从库 | 手动加回 |
| 分片库宕机 | ShardingSphere 心跳 + Sentinel 熔断 | 路由到该分片的请求全部降级 | 数据补偿同步 |
| 连接池耗尽 | Druid activeCount >= maxActive | Sentinel 限流拒新请求 | 排查慢 SQL → 扩容 |

#### 5.4.2 Sentinel 熔断降级规则

```java
/**
 * Sentinel 规则配置（在 Nacos 中可动态调整）
 */
@Configuration
public class SentinelRulesManager {

    @PostConstruct
    public void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();

        // 慢 SQL 比例熔断：1 分钟内慢调用比例 > 30% → 熔断 60s
        DegradeRule slowSqlRule = new DegradeRule("code_submission_query")
                .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                .setCount(1000)  // 平均 RT > 1000ms
                .setTimeWindow(60);

        // 异常比例熔断
        DegradeRule errorRule = new DegradeRule("question_query")
                .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                .setCount(0.3)
                .setTimeWindow(30);

        rules.add(slowSqlRule);
        rules.add(errorRule);
        DegradeRuleManager.loadRules(rules);
    }
}
```

#### 5.4.3 数据备份策略

| 备份类型 | 频率 | 保留周期 | 工具 |
|---------|-----|---------|------|
| 全量备份 | 每日凌晨 2:00 | 7 天 | xtrabackup / mysqldump |
| 增量备份（binlog）| 实时 | 30 天 | MySQL binlog |
| ES 快照 | 每日凌晨 4:00 | 7 天 | ES snapshot API |
| 异地备份 | 每日 | 30 天 | 对象存储（已有 COS） |

### 5.5 扩展性设计

#### 未来分片扩容方案

```
当前: 2 库 × 4 表（code_submission）
   │
   │  数据增长超过 70% 容量 → 触发扩容
   ▼
扩容: 4 库 × 8 表
```

**扩容步骤**：
1. 准备新数据库实例（ds2、ds3）
2. 修改分片规则：`ds$->{0..3}.code_submission_$->{0..7}`
3. 使用 `userId % 4` 分库、`userId % 8` 分表
4. 通过数据迁移工具将旧数据按新规则重分布
5. 灰度切换流量到新架构

```java
/**
 * 在线扩容工具：一致性哈希迁移
 * 优势：只需迁移约 50% 数据（1→2库），而非全量
 */
public class ConsistentHashMigrator {
    // 使用 Ketama 一致性哈希环
    // 扩容时仅影响相邻节点数据


    // ... 具体实现
}
```

---

## 六、实施与验证计划

### 6.1 分阶段实施步骤

```
阶段一: 开发环境验证（第 1-2 周）
├── 引入 ShardingSphere-JDBC 5.4.1 依赖
├── 本地搭建 2 个 MySQL 实例
├── 配置分片规则（YAML 文件方式）
├── 改造 Mapper 层（确保所有查询带分片键）
├── 单元测试 + 集成测试
└── 验收: 所有 CRUD 通过，分片路由正确

阶段二: 测试环境压测（第 3-4 周）
├── 部署完整分库分表架构
├── 数据迁移（从单库→分片库）
├── JUnit 压测 + JMeter 压测
├── 慢 SQL 监控接入 + 告警测试
├── 读写分离 + 故障切换演练
└── 验收: QPS > 1000，P99 < 500ms，0 数据不一致

阶段三: 生产环境灰度（第 5-6 周）
├── 10% 流量 → 分片库（双写校验）
├── 50% 流量 → 分片库（数据一致性校验）
├── 100% 流量 → 分片库
├── 旧库保留 1 个月作为回滚备份
└── 验收: 业务正常，无用户投诉，监控指标稳定
```

### 6.2 功能验证指标

| 验证项 | 方法 | 通过标准 |
|-------|------|---------|
| 分片路由正确性 | 日志 + SQL 审计 | 100% SQL 路由到预期分片 |
| 数据一致性 | 逐条 MD5 对比 | 新旧库 100% 一致 |
| 读写分离路由 | 全链路日志追踪 | 读 100% 走从库，写 100% 走主库 |
| 分布式事务 | 并发提交 + 异常回滚测试 | ACID 保证 |
| 分页正确性 | 已知数据集对比 | 排序 + 总数一致 |

### 6.3 性能测试方案

#### JMeter 压测用例

| 场景 | 并发数 | 持续时间 | 目标 |
|------|-------|---------|------|
| 题目查询（带分片键） | 500 | 300s | P99 < 200ms |
| 代码提交（带 userId） | 200 | 600s | P99 < 500ms, 0 错误 |
| 题目列表搜索（ES） | 1000 | 300s | P99 < 300ms |
| 批量写入 | 100 | 600s | 吞吐 > 5000 TPS |

#### 关键监控指标

| 指标 | 目标值 | 告警阈值 |
|------|-------|---------|
| 平均响应时间 | < 100ms | > 500ms |
| P99 响应时间 | < 500ms | > 1000ms |
| 慢 SQL 比例 | < 1% | > 5% |
| 连接池使用率 | < 70% | > 85% |
| 错误率 | < 0.1% | > 1% |
| QPS | > 1000 | — |

### 6.4 回滚机制设计

```java
/**
 * 回滚决策矩阵
 */
public enum RollbackStrategy {

    /**
     * L1 回滚（即时）：出现大量数据不一致
     * 动作：Nacos 配置切换 → ShardingDataSource → 旧库 DataSource
     * 影响：< 5 分钟
     */
    L1_INSTANT,

    /**
     * L2 回滚（计划内）：慢 SQL 比例 > 10% 持续 5 分钟
     * 动作：切换部分读流量回旧库
     * 影响：< 15 分钟
     */
    L2_PLANNED,

    /**
     * L3 回滚（完整）：主库全部下线
     * 动作：Nacos 下发 → 全量切回旧库
     * 影响：< 30 分钟
     */
    L3_FULL
}
```

**回滚配置（Nacos 动态下发）**：

```yaml
# Nacos DataId: mianmianshi-sharding-rollback.yaml
sharding:
  rollback:
    # 回滚开关：true = 切回旧库
    enabled: false
    # 回滚级别：L1 / L2 / L3
    level: L1_INSTANT
    # 影响范围：ALL / QUESTION / SUBMISSION
    scope: ALL
```

**回滚后数据补偿**：

```sql
-- 回滚后，将在分片库中产生但在旧库中缺失的数据补回
INSERT INTO old_db.code_submission
SELECT * FROM ds0.code_submission_0 WHERE create_time > '回滚时间点'
UNION ALL
SELECT * FROM ds0.code_submission_1 WHERE create_time > '回滚时间点'
UNION ALL
SELECT * FROM ds1.code_submission_0 WHERE create_time > '回滚时间点'
UNION ALL
SELECT * FROM ds1.code_submission_1 WHERE create_time > '回滚时间点';
```

---

## 七、运维与监控体系

### 7.1 日常运维操作指南

#### 7.1.1 新增分片表

```sql
-- 1. 在新数据库创建表结构（所有分片）
-- ds0
CREATE TABLE ds0.question_2 LIKE ds0.question_0;
CREATE TABLE ds1.question_2 LIKE ds1.question_0;

-- 2. 更新 ShardingSphere 配置 → Nacos 推送生效
```

#### 7.1.2 数据归档

```sql
-- mq_sync_record 归档：2024 年老数据 → 冷库
-- 1. 导出
mysqldump -h ... --where="create_time < '2025-01-01'" \
    ds0 mq_sync_record_2024 | gzip > mq_2024_ds0.sql.gz

-- 2. 删除
DELETE FROM ds0.mq_sync_record_2024 WHERE create_time < '2025-01-01' LIMIT 10000;
-- 分批循环删除，避免长事务
```

#### 7.1.3 连接池紧急扩容

```java
// 通过 Nacos 动态调整（无需重启）
// DataId: druid-config
{
    "ds0.maxActive": 200,   // 从 100 → 200
    "ds1.maxActive": 200
}
```

### 7.2 监控指标体系

| 层级 | 指标 | 采集方式 | 展示面板 | 告警 |
|------|------|---------|---------|------|
| **基础设施** | CPU / 内存 / 磁盘 / 网络 | Prometheus + Node Exporter | Grafana | N/A |
| **MySQL** | QPS / TPS / 连接数 / 复制延迟 | MySQL Exporter | Grafana | 复制延迟 > 5s |
| **Druid** | 连接池活跃数 / 等待数 / 慢SQL数 | DruidStatService | Druid 控制台 | 活跃 > 85% |
| **ShardingSphere** | 分片路由耗时 / 归并耗时 | 自定义 Metrics | Grafana | 归并 > 100ms |
| **应用层** | 接口 RT / QPS / 错误率 | Sentinel Dashboard | Sentinel 控制台 | 错误率 > 1% |
| **业务** | 代码提交成功率 | 自定义埋点 | Grafana | 成功率 < 95% |

### 7.3 问题排查流程

```
用户反馈: 代码提交很慢
           │
   ┌───────▼────────┐
   │ 1. Sentinel 确认 │─── 是否触发限流/熔断? → YES → 调整/扩容
   │    接口状态      │
   └───────┬────────┘
           │
   ┌───────▼────────┐
   │ 2. Druid 监控页  │─── 查看慢 SQL 列表? → 发现慢 SQL →
   │    /druid/sql   │    分析原因: 缺索引 / SQL 未带分片键 / 数据倾斜
   └───────┬────────┘
           │
   ┌───────▼────────┐
   │ 3. 全链路日志    │─── 定位到具体的 SQL + 参数
   │    TraceId      │    └→ 确认分片路由是否正确
   └───────┬────────┘
           │
   ┌───────▼────────┐
   │ 4. MySQL 层面    │─── EXPLAIN 分析执行计划
   │    数据库直连    │    └→ 索引命中? / 扫描行数?
   └───────┬────────┘
           │
   ┌───────▼────────┐
   │ 5. 修复 + 验证   │─── 添加索引 / 改写 SQL / 调整分片策略
   └────────────────┘
```

### 7.4 性能调优方法论

```
调优闭环:

  Monitor → Analyze → Hypothesize → Change → Verify → Monitor
     ▲                                                    │
     └────────────────────────────────────────────────────┘

具体实践:
  1. 基准备：压测获取基准 QPS/RT
  2. 分析瓶颈：慢 SQL → 执行计划 → 索引/分片键
  3. 修改一个变量 (One Change at a Time)
  4. 重新压测 → 对比指标
  5. 记录调优笔记
```

#### 常见问题排查清单

| 现象 | 可能原因 | 排查手段 | 解决方案 |
|------|---------|---------|---------|
| 某分片库 QPS 异常高 | 数据分布不均 | `SELECT COUNT(*)` 各分片 | 检查分片算法；考虑扩分片数 |
| 慢 SQL 突然增多 | 新功能未带分片键 | Druid 慢 SQL 列表 | 修复代码；添加分片键校验拦截器 |
| 连接池耗尽 | 慢 SQL 阻塞 | Druid 活跃连接数图表 | 先 kill 慢查询 → 优化 SQL |
| 读写分离延迟 | 主从同步延迟 | `SHOW SLAVE STATUS` | 关键读操作 Hint 强制走主库 |
| 分片路由时间过大 | 分片规则复杂 | ShardingSphere 日志 | 简化 INLINE 表达式或算法 |

---

## 八、依赖与配置汇总

### 8.1 Maven 依赖（pom.xml 新增）

```xml
<!-- ShardingSphere-JDBC 5.4.1 (Java 8 兼容) -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core</artifactId>
    <version>5.4.1</version>
</dependency>

<!-- ShardingSphere 与 Spring Boot 集成 -->
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core-spring-boot-starter</artifactId>
    <version>5.4.1</version>
</dependency>

<!-- Seata (分布式事务) → 与 Nacos 集成 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
    <version>2021.0.5.0</version>
    <exclusions>
        <exclusion>
            <groupId>io.seata</groupId>
            <artifactId>seata-spring-boot-starter</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>io.seata</groupId>
    <artifactId>seata-spring-boot-starter</artifactId>
    <version>1.7.1</version>
</dependency>
```

### 8.2 完整配置文件一览

| 文件 | 说明 |
|------|------|
| `application.yml` | 原有基础配置（Druid 参数调优） |
| `application-sharding.yml` | ShardingSphere 分片规则 |
| `application-seata.yml` | Seata 分布式事务配置 |
| `application-druid-monitor.yml` | Druid 监控增强配置 |
| Nacos `mianmianshi-sharding.yaml` | 动态分片配置（支持运行时切换） |
| Nacos `mianmianshi-rollback.yaml` | 回滚开关配置 |

### 8.3 代码改造清单

| 模块 | 改造内容 | 影响范围 |
|------|---------|---------|
| **pom.xml** | 新增 ShardingSphere + Seata 依赖 | 构建 |
| **配置类** | 新增 ShardingSphereConfig、DruidMonitorConfig | 启动 |
| **Mapper 层** | 所有 SQL 确保带分片键 | `*Mapper.java` / `*.xml` |
| **Service 层** | 跨库查询拆分、分布式事务注解 | `*ServiceImpl.java` |
| **实体类** | 确认 @TableName 与分片表一致 | `model/entity/*.java` |
| **分片算法** | 新增 UserIdModShardingAlgorithm 等 | `sharding/` |
| **迁移工具** | 新增 FullDataMigrator | `migration/` |
| **监控模块** | 新增 SlowSqlCollector / SlowSqlAnalyzer | `monitor/` |
| **告警模块** | 新增 SlowSqlAlertEngine / AlarmHandler | `monitor/alarm/` |

---

## 九、验收标准总结

| 阶段 | 验收项 | 通过标准 |
|------|-------|---------|
| 开发环境 | 单测 / 集成测试 | 覆盖率 > 80%，所有分片路由正确 |
| 测试环境 | 压力测试 | QPS > 1000，慢 SQL 比例 < 1%，0 数据不一致 |
| 灰度 10% | 线上观察 24h | 无慢 SQL 告警，无数据不一致 |
| 灰度 50% | 线上观察 48h | 业务指标持平，Druid 监控正常 |
| 全量 | 线上观察 1 周 | 所有指标稳定，旧库可安全下线 |

---

> **文档版本**: v1.0  
> **适用项目**: mianmianshi-backend (Spring Boot 2.7.2 + Java 8 + MyBatis-Plus 3.5.2)  
> **最后更新**: 2026-08-07
