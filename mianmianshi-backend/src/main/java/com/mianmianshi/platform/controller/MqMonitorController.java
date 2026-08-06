package com.mianmianshi.platform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mianmianshi.platform.common.BaseResponse;
import com.mianmianshi.platform.common.ErrorCode;
import com.mianmianshi.platform.common.ResultUtils;
import com.mianmianshi.platform.constant.MqConstant;
import com.mianmianshi.platform.exception.BusinessException;
import com.mianmianshi.platform.exception.ThrowUtils;
import com.mianmianshi.platform.model.entity.MqSyncRecord;
import com.mianmianshi.platform.service.SyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * MQ 同步任务监控与查询接口
 *
 * <h3>提供能力</h3>
 * <ul>
 *   <li>GET  /mq/stats     — 实时统计：消息吞吐量、成功率、失败率</li>
 *   <li>GET  /mq/stats/detail — 详细指标：最近1分钟的 success/failed/skipped/avgLatency</li>
 *   <li>GET  /mq/records   — 按状态分页查询同步记录</li>
 *   <li>GET  /mq/record/{questionId} — 追踪单个题目的同步状态</li>
 *   <li>POST /mq/alert/check — 手动触发告警检查</li>
 * </ul>
 *
 * @author mianmianshi
 */
@Slf4j
@RestController
@RequestMapping("/mq")
public class MqMonitorController {

    @Resource
    private SyncTaskService syncTaskService;

    /**
     * 获取简要统计（仪表盘用）
     *
     * <p>返回：
     * <ul>
     *   <li>total: 最近 1 分钟消息数</li>
     *   <li>success: 成功数</li>
     *   <li>failed: 失败数</li>
     *   <li>successRate: 成功率 (%)</li>
     *   <li>avgLatencyMs: 平均延迟</li>
     * </ul>
     */
    @GetMapping("/stats")
    public BaseResponse<Map<String, Object>> getStats() {
        Map<String, Object> stats = syncTaskService.getRecentStats();
        if (stats == null) {
            stats = new HashMap<>();
            stats.put("total", 0);
            stats.put("success", 0);
            stats.put("failed", 0);
        }

        // 计算成功率
        Object totalObj = stats.get("total");
        Object successObj = stats.get("success");
        long total = totalObj != null ? ((Number) totalObj).longValue() : 0;
        long success = successObj != null ? ((Number) successObj).longValue() : 0;
        double successRate = total > 0 ? (double) success / total * 100 : 100.0;
        stats.put("successRate", String.format("%.1f%%", successRate));

        return ResultUtils.success(stats);
    }

    /**
     * 获取详细指标（包含告警信息）
     */
    @GetMapping("/stats/detail")
    public BaseResponse<Map<String, Object>> getDetailStats() {
        Map<String, Object> stats = syncTaskService.getRecentStats();
        if (stats == null) {
            stats = new HashMap<>();
        }

        Map<String, Object> detail = new HashMap<>(stats);

        // 告警判断
        Object totalObj = stats.get("total");
        Object failedObj = stats.get("failed");
        long total = totalObj != null ? ((Number) totalObj).longValue() : 0;
        long failed = failedObj != null ? ((Number) failedObj).longValue() : 0;

        boolean backlogAlert = total > MqConstant.ALERT_QUEUE_BACKLOG;
        boolean failRateAlert = total > 0
                && (double) failed / total * 100 > MqConstant.ALERT_FAIL_RATE;

        detail.put("backlogAlert", backlogAlert);
        detail.put("failRateAlert", failRateAlert);
        detail.put("alertMessage",
                (backlogAlert ? "消息积压超过阈值(" + MqConstant.ALERT_QUEUE_BACKLOG + ") " : "")
                + (failRateAlert ? "失败率超过阈值(" + MqConstant.ALERT_FAIL_RATE + "%)" : ""));

        return ResultUtils.success(detail);
    }

    /**
     * 分页查询同步记录
     *
     * @param status  状态筛选（PROCESSING/SUCCESS/FAILED/SKIPPED）可为空
     * @param current 当前页
     * @param size    每页大小
     */
    @GetMapping("/records")
    public BaseResponse<Page<MqSyncRecord>> listRecords(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        ThrowUtils.throwIf(size > 100, ErrorCode.PARAMS_ERROR, "每页最大 100 条");
        Page<MqSyncRecord> page = syncTaskService.listByStatus(status, current, size);
        return ResultUtils.success(page);
    }

    /**
     * 根据 questionId 查询最近一条同步记录
     *
     * <p>用于追踪单个同步任务的执行状态</p>
     */
    @GetMapping("/record/{questionId}")
    public BaseResponse<MqSyncRecord> getRecordByQuestionId(
            @PathVariable Long questionId) {
        ThrowUtils.throwIf(questionId == null || questionId <= 0,
                ErrorCode.PARAMS_ERROR);
        MqSyncRecord record = syncTaskService.getByQuestionId(questionId);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,
                    "未找到该题目的同步记录");
        }
        return ResultUtils.success(record);
    }

    /**
     * 手动触发告警检查
     *
     * <p>检查项：
     * <ol>
     *   <li>最近 1 分钟失败率是否超过 10%</li>
     *   <li>最近 1 分钟消息量是否超过积压阈值</li>
     * </ol>
     */
    @PostMapping("/alert/check")
    public BaseResponse<Map<String, Object>> checkAlert() {
        Map<String, Object> stats = syncTaskService.getRecentStats();
        if (stats == null) {
            return ResultUtils.success(Map.of("alert", false, "message", "无数据"));
        }

        long total = ((Number) stats.getOrDefault("total", 0)).longValue();
        long failed = ((Number) stats.getOrDefault("failed", 0)).longValue();

        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("failed", failed);

        if (total > MqConstant.ALERT_QUEUE_BACKLOG) {
            result.put("alert", true);
            result.put("level", "WARN");
            result.put("type", "BACKLOG");
            result.put("message", String.format(
                    "消息积压警告: 1分钟内 %d 条消息, 阈值 %d",
                    total, MqConstant.ALERT_QUEUE_BACKLOG));
            log.warn("[MQ-ALERT] 消息积压: total={}", total);
        } else if (total > 0
                && (double) failed / total * 100 > MqConstant.ALERT_FAIL_RATE) {
            result.put("alert", true);
            result.put("level", "ERROR");
            result.put("type", "FAIL_RATE");
            result.put("message", String.format(
                    "失败率过高: %.1f%%, 阈值 %.1f%%",
                    (double) failed / total * 100,
                    MqConstant.ALERT_FAIL_RATE));
            log.error("[MQ-ALERT] 失败率过高: failed={}, total={}", failed, total);
        } else {
            result.put("alert", false);
            result.put("message", "系统正常");
        }

        return ResultUtils.success(result);
    }
}
