package com.mianmianshi.platform.service;

import com.mianmianshi.platform.model.dto.codesubmission.CodeExecutionResult;
import com.mianmianshi.platform.model.entity.CodeSubmission;

/**
 * 代码提交服务接口
 */
public interface CodeSubmissionService {

    /**
     * 提交代码并执行判题
     *
     * @param submission 提交信息
     * @param userId     用户ID
     * @return 判题结果
     */
    CodeExecutionResult submitAndJudge(CodeSubmission submission, Long userId);
}
