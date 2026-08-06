package com.mianmianshi.platform.controller;

import com.mianmianshi.platform.common.BaseResponse;
import com.mianmianshi.platform.common.ErrorCode;
import com.mianmianshi.platform.common.ResultUtils;
import com.mianmianshi.platform.exception.BusinessException;
import com.mianmianshi.platform.exception.ThrowUtils;
import com.mianmianshi.platform.model.dto.codesubmission.CodeExecutionResult;
import com.mianmianshi.platform.model.dto.codesubmission.CodeSubmissionRequest;
import com.mianmianshi.platform.model.entity.CodeSubmission;
import com.mianmianshi.platform.model.entity.User;
import com.mianmianshi.platform.service.CodeSubmissionService;
import com.mianmianshi.platform.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;

/**
 * 代码提交接口 - 处理用户代码提交与判题
 */
@RestController
@RequestMapping("/code")
@Slf4j
public class CodeSubmissionController {

    @Resource
    private CodeSubmissionService codeSubmissionService;

    @Resource
    private UserService userService;

    /**
     * 提交代码并执行判题
     *
     * @param codeSubmissionRequest 提交请求（题目ID、语言、代码）
     * @param request               HTTP请求（获取登录用户）
     * @return 判题结果
     */
    @PostMapping("/submit")
    public BaseResponse<CodeExecutionResult> submitCode(
            @RequestBody CodeSubmissionRequest codeSubmissionRequest,
            HttpServletRequest request) {
        // 参数校验
        if (codeSubmissionRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long questionId = codeSubmissionRequest.getQuestionId();
        String language = codeSubmissionRequest.getLanguage();
        String code = codeSubmissionRequest.getCode();
        if (questionId == null || questionId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目ID不能为空");
        }
        if (language == null || language.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言不能为空");
        }
        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码不能为空");
        }

        // 获取登录用户
        User loginUser = userService.getLoginUser(request);

        // 构建提交记录
        CodeSubmission submission = new CodeSubmission();
        submission.setQuestionId(questionId);
        submission.setLanguage(language);
        submission.setCode(code);

        // 执行判题
        CodeExecutionResult result = codeSubmissionService.submitAndJudge(submission, loginUser.getId());
        return ResultUtils.success(result);
    }
}
