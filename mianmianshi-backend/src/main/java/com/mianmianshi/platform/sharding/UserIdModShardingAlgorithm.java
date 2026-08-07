package com.mianmianshi.platform.sharding;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * 基于 userId 取模的分片算法
 * <p>
 * 用于 code_submission 表：按 userId 路由到不同的库和表。
 * 同一个用户的所有提交记录落在同一个分片中，避免跨库查询。
 *
 * <p>使用示例（在 YAML 中注册）：
 * <pre>{@code
 *   shardingAlgorithms:
 *     cs_mod:
 *       type: CLASS_BASED
 *       props:
 *         strategy: STANDARD
 *         algorithmClassName: com.mianmianshi.platform.sharding.UserIdModShardingAlgorithm
 * }</pre>
 *
 * @author mianmianshi
 */
public final class UserIdModShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private int shardingCount = 4;

    @Override
    public String doSharding(final Collection<String> availableTargetNames,
                             final PreciseShardingValue<Comparable<?>> shardingValue) {
        long userId = ((Number) shardingValue.getValue()).longValue();
        int mod = (int) (Math.abs(userId) % shardingCount);
        String suffix = String.valueOf(mod);

        for (String name : availableTargetNames) {
            if (name.endsWith("_" + suffix) || name.equals(suffix)) {
                return name;
            }
        }
        // 降级：取第一个可用节点
        return availableTargetNames.iterator().next();
    }

    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final RangeShardingValue<Comparable<?>> shardingValue) {
        // 范围查询广播到所有分片
        return availableTargetNames;
    }

    @Override
    public Properties getProps() {
        Properties props = new Properties();
        props.setProperty("sharding-count", String.valueOf(shardingCount));
        return props;
    }

    @Override
    public String getType() {
        return "USER_ID_MOD";
    }

    @Override
    public void init(final Properties props) {
        String count = props.getProperty("sharding-count", "4");
        this.shardingCount = Integer.parseInt(count);
    }

    @Override
    public Range<Comparable<?>> getEffectiveShardingRange(final RangeShardingValue<Comparable<?>> shardingValue) {
        return shardingValue.getValueRange();
    }
}
