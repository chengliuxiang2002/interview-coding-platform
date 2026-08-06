package com.mianmianshi.platform.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 代码提交记录实体
 */
@TableName(value = "code_submission")
@Data
public class CodeSubmission implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 题目 id
     */
    private Long questionId;

    /**
     * 提交用户 id
     */
    private Long userId;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 提交的代码
     */
    private String code;

    /**
     * 判题状态：PENDING(等待中)、RUNNING(运行中)、ACCEPTED(通过)、WRONG_ANSWER(答案错误)、
     * COMPILE_ERROR(编译错误)、RUNTIME_ERROR(运行错误)、TIME_LIMIT_EXCEEDED(超时)
     */
    private String status;

    /**
     * 通过测试用例数
     */
    private Integer passedCases;

    /**
     * 总测试用例数
     */
    private Integer totalCases;

    /**
     * 执行时间（毫秒）
     */
    private Long executeTimeMs;

    /**
     * 执行内存（KB）
     */
    private Long memoryUsageKb;

    /**
     * 判题结果详情（JSON，包含每个测试用例的结果）
     */
    private String judgeResult;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
