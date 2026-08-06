package com.mianmianshi.platform.model.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * 题目实体
 * @TableName question
 */
@TableName(value ="question")
@Data
public class Question implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 标题
     */
    private String title;

    /**
     * 内容（题目描述，支持 Markdown）
     */
    private String content;

    /**
     * 难度：easy(简单)、medium(中等)、hard(困难)
     */
    private String difficulty;

    /**
     * 标签列表（json 数组）
     */
    private String tags;

    /**
     * 推荐答案
     */
    private String answer;

    /**
     * 支持的编程语言列表（JSON 数组，如 ["java","python","javascript"]）
     */
    private String supportedLanguages;

    /**
     * 代码模板（JSON 对象，key为语言，value为模板代码）
     */
    private String codeTemplate;

    /**
     * 测试用例（JSON 数组，每个用例包含 input 和 expectedOutput）
     */
    private String testCases;

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
     * 编辑时间
     */
    private Date editTime;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}