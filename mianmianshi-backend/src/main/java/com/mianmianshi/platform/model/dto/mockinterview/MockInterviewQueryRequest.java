package com.mianmianshi.platform.model.dto.mockinterview;

import com.mianmianshi.platform.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 查询模拟面试请求
 *
 * @author <a href="https://github.com/liyupi">程序员鱼�?/a>
 * @from <a href="https://www.code-nav.cn">编程导航学习�?/a>
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class MockInterviewQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 工作年限
     */
    private String workExperience;

    /**
     * 工作岗位
     */
    private String jobPosition;

    /**
     * 面试难度
     */
    private String difficulty;

    /**
     * 状态（0-待开始�?-进行中�?-已结束）
     */
    private Integer status;

    /**
     * 创建用户 id
     */
    private Long userId;

    private static final long serialVersionUID = 1L;
}