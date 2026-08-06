package com.mianmianshi.platform.model.dto.questionBankQuestion;

import com.mianmianshi.platform.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 查询题库题目关联请求
 *
 * @author <a href="https://github.com/liyupi">程序员鱼�?/a>
 * @from <a href="https://www.code-nav.cn">编程导航学习�?/a>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class QuestionBankQuestionQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * id
     */
    private Long notId;

    /**
     * 题库 id
     */
    private Long questionBankId;

    /**
     * 题目 id
     */
    private Long questionId;

    /**
     * 创建用户 id
     */
    private Long userId;

    private static final long serialVersionUID = 1L;
}