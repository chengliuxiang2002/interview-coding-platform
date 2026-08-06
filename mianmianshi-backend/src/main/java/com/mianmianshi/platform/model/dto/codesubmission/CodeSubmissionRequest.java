package com.mianmianshi.platform.model.dto.codesubmission;

import lombok.Data;
import java.io.Serializable;

/**
 * 代码提交请求
 */
@Data
public class CodeSubmissionRequest implements Serializable {

    /**
     * 题目 id
     */
    private Long questionId;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 提交的代码
     */
    private String code;

    private static final long serialVersionUID = 1L;
}
