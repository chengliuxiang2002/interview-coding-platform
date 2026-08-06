package com.mianmianshi.platform.model.vo;

import cn.hutool.json.JSONUtil;
import com.mianmianshi.platform.model.entity.Question;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 题目视图（含用户信息）
 */
@Data
public class QuestionVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容（Markdown 题目描述）
     */
    private String content;

    /**
     * 难度
     */
    private String difficulty;

    /**
     * 推荐答案
     */
    private String answer;

    /**
     * 支持的编程语言列表
     */
    private List<String> supportedLanguages;

    /**
     * 代码模板（语言 -> 模板代码）
     */
    private Map<String, String> codeTemplate;

    /**
     * 测试用例列表
     */
    private List<Map<String, String>> testCases;

    /**
     * 通过次数
     */
    private Integer acceptedNum;

    /**
     * 提交次数
     */
    private Integer submitNum;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 标签列表
     */
    private List<String> tagList;

    /**
     * 创建用户信息
     */
    private UserVO user;

    /**
     * 封装类转对象
     *
     * @param questionVO
     * @return
     */
    public static Question voToObj(QuestionVO questionVO) {
        if (questionVO == null) {
            return null;
        }
        Question question = new Question();
        BeanUtils.copyProperties(questionVO, question);
        List<String> tagList = questionVO.getTagList();
        question.setTags(JSONUtil.toJsonStr(tagList));
        return question;
    }

    /**
     * 对象转封装类
     */
    public static QuestionVO objToVo(Question question) {
        if (question == null) {
            return null;
        }
        QuestionVO questionVO = new QuestionVO();
        BeanUtils.copyProperties(question, questionVO);
        // 解析 JSON 字段
        questionVO.setTagList(JSONUtil.toList(JSONUtil.parseArray(question.getTags()), String.class));
        if (question.getSupportedLanguages() != null) {
            questionVO.setSupportedLanguages(JSONUtil.toList(JSONUtil.parseArray(question.getSupportedLanguages()), String.class));
        }
        if (question.getCodeTemplate() != null) {
            questionVO.setCodeTemplate(JSONUtil.toBean(question.getCodeTemplate(), Map.class));
        }
        if (question.getTestCases() != null) {
            questionVO.setTestCases(JSONUtil.toList(JSONUtil.parseArray(question.getTestCases()), Map.class));
        }
        return questionVO;
    }
}
