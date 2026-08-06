package com.mianmianshi.platform.model.dto.question;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 生成题目请求
 *
 * @author <a href="https://github.com/liyupi">程序员鱼�?/a>
 * @from <a href="https://www.code-nav.cn">编程导航学习�?/a>
 */
@Data
public class QuestionAIGenerateRequest implements Serializable {

    /**
     * 题目类型，比�?Java
     */
    private String questionType;

    /**
     * 题目数量，比�?10
     */
    private int number = 10;

    private static final long serialVersionUID = 1L;
}