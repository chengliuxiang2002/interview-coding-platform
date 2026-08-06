package com.mianmianshi.platform.model.enums;

import org.apache.commons.lang3.ObjectUtils;

/**
 * 判题状态枚举
 */
public enum CodeSubmissionStatusEnum {

    PENDING("PENDING", "等待中"),
    RUNNING("RUNNING", "运行中"),
    ACCEPTED("ACCEPTED", "通过"),
    WRONG_ANSWER("WRONG_ANSWER", "答案错误"),
    COMPILE_ERROR("COMPILE_ERROR", "编译错误"),
    RUNTIME_ERROR("RUNTIME_ERROR", "运行错误"),
    TIME_LIMIT_EXCEEDED("TIME_LIMIT_EXCEEDED", "超时");

    private final String value;
    private final String text;

    CodeSubmissionStatusEnum(String value, String text) {
        this.value = value;
        this.text = text;
    }

    public String getValue() {
        return value;
    }

    public String getText() {
        return text;
    }
}
