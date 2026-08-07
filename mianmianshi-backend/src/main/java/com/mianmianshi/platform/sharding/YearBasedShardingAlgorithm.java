package com.mianmianshi.platform.sharding;

import com.google.common.collect.Range;
import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于年份的分片算法
 * <p>
 * 用于 mq_sync_record 表：按 create_time 的年份路由到对应年份表。
 * 支持按年数据归档——过期的年份表可直接 drop。
 *
 * @author mianmianshi
 */
public final class YearBasedShardingAlgorithm implements StandardShardingAlgorithm<Comparable<?>> {

    private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter YEAR_MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MM");

    /** 起始年份，用于生成 actualDataNodes */
    private int startYear = 2025;
    private int endYear = 2030;

    @Override
    public String doSharding(final Collection<String> availableTargetNames,
                             final PreciseShardingValue<Comparable<?>> shardingValue) {
        String yearSuffix = resolveYearSuffix(shardingValue.getValue());

        for (String name : availableTargetNames) {
            if (name.endsWith(yearSuffix) || name.contains(yearSuffix)) {
                return name;
            }
        }
        // 降级：取最后一个节点（最新年份的分片）
        List<String> list = new ArrayList<>(availableTargetNames);
        list.sort(Comparator.reverseOrder());
        return list.get(0);
    }

    @Override
    public Collection<String> doSharding(final Collection<String> availableTargetNames,
                                         final RangeShardingValue<Comparable<?>> shardingValue) {
        Range<Comparable<?>> range = shardingValue.getValueRange();
        Set<String> result = new LinkedHashSet<>();

        // 收集范围内涉及的所有年份后缀
        int minYear = resolveYear(range.hasLowerBound() ? range.lowerEndpoint() : null);
        int maxYear = resolveYear(range.hasUpperBound() ? range.upperEndpoint() : null);

        for (String name : availableTargetNames) {
            for (int y = minYear; y <= maxYear; y++) {
                String suffix = "_" + y;
                if (name.endsWith(suffix)) {
                    result.add(name);
                    break;
                }
            }
        }
        return result.isEmpty() ? availableTargetNames : result;
    }

    private String resolveYearSuffix(Object value) {
        if (value instanceof Date) {
            return String.valueOf(((Date) value).toInstant()
                    .atZone(ZoneId.systemDefault()).getYear());
        }
        if (value instanceof java.sql.Timestamp) {
            return String.valueOf(((java.sql.Timestamp) value).toLocalDateTime().getYear());
        }
        if (value instanceof java.sql.Date) {
            return String.valueOf(((java.sql.Date) value).toLocalDate().getYear());
        }
        if (value instanceof Long) {
            long millis = (Long) value;
            return String.valueOf(Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault()).getYear());
        }
        return String.valueOf(LocalDate.now().getYear());
    }

    private int resolveYear(Object value) {
        if (value == null) return startYear;
        try {
            return Integer.parseInt(resolveYearSuffix(value));
        } catch (NumberFormatException e) {
            return LocalDate.now().getYear();
        }
    }

    @Override
    public Properties getProps() {
        Properties props = new Properties();
        props.setProperty("start-year", String.valueOf(startYear));
        props.setProperty("end-year", String.valueOf(endYear));
        return props;
    }

    @Override
    public String getType() {
        return "YEAR_BASED";
    }

    @Override
    public void init(final Properties props) {
        this.startYear = Integer.parseInt(props.getProperty("start-year", "2025"));
        this.endYear = Integer.parseInt(props.getProperty("end-year", "2030"));
    }
}
