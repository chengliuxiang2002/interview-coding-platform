package com.mianmianshi.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mianmianshi.platform.model.entity.MqSyncRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * MQ 同步记录 Mapper
 *
 * @author mianmianshi
 */
@Mapper
public interface MqSyncRecordMapper extends BaseMapper<MqSyncRecord> {

    /**
     * 查询某 questionId 最后一次成功处理的版本号
     */
    @Select("SELECT MAX(version) FROM mq_sync_record "
            + "WHERE question_id = #{questionId} AND status = 'SUCCESS'")
    Long selectLastProcessedVersion(@Param("questionId") Long questionId);

    /**
     * 统计最近 1 分钟内的处理指标
     *
     * @return Map 包含 total（总数）、success（成功数）、failed（失败数）
     */
    @Select("SELECT "
            + "  COUNT(*) as total, "
            + "  SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as success, "
            + "  SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed "
            + "FROM mq_sync_record "
            + "WHERE create_time >= DATE_SUB(NOW(), INTERVAL 1 MINUTE)")
    Map<String, Object> selectRecentStats();
}
