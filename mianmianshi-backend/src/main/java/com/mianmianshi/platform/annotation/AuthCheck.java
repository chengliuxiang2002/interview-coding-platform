package com.mianmianshi.platform.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限校验
 *
 * @author <a href="https://github.com/liyupi">程序员鱼�?/a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthCheck {

    /**
     * 必须有某个角�?
     *
     * @return
     */
    String mustRole() default "";

}

