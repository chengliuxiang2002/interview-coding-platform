package com.mianmianshi.platform.model.dto.codesubmission;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 代码执行结果
 */
@Data
public class CodeExecutionResult implements Serializable {

    /**
     * 是否全部通过
     */
    private Boolean allPassed;

    /**
     * 通过用例数
     */
    private Integer passedCases;

    /**
     * 总用例数
     */
    private Integer totalCases;

    /**
     * 判题状态
     */
    private String status;

    /**
     * 总执行时间（毫秒）
     */
    private Long executeTimeMs;

    /**
     * 各测试用例执行结果
     */
    private List<TestCaseResult> testCaseResults;

    /**
     * 错误信息
     */
    private String errorMessage;

    @Data
    public static class TestCaseResult implements Serializable {
        /**
         * 测试用例编号
         */
        private Integer index;

        /**
         * 是否通过
         */
        private Boolean passed;

        /**
         * 输入
         */
        private String input;

        /**
         * 期望输出
         */
        private String expectedOutput;

        /**
         * 实际输出
         */
        private String actualOutput;

        /**
         * 执行时间（毫秒）
         */
        private Long executeTimeMs;

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
