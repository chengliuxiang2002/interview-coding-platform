package com.mianmianshi.platform.config;

import com.alibaba.druid.support.http.StatViewServlet;
import com.alibaba.druid.support.http.WebStatFilter;
import com.alibaba.druid.support.spring.stat.DruidStatInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.JdkRegexpMethodPointcut;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Druid 多数据源监控配置
 * <p>
 * 接入 ShardingSphere 后，需显式注册监控组件：
 * <ul>
 *   <li>Spring AOP 方法级耗时监控</li>
 *   <li>WebStatFilter — HTTP 请求关联监控</li>
 *   <li>StatViewServlet — 监控页面 /druid/*</li>
 * </ul>
 *
 * @author mianmianshi
 */
@Configuration
public class DruidMonitorConfig {

    /**
     * Spring 方法级监控拦截器
     * 对 DAO/Service 层的 JDBC 执行时间进行采集
     */
    @Bean
    public DruidStatInterceptor druidStatInterceptor() {
        return new DruidStatInterceptor();
    }

    @Bean
    public DefaultPointcutAdvisor druidStatAdvisor(DruidStatInterceptor interceptor) {
        JdkRegexpMethodPointcut pointcut = new JdkRegexpMethodPointcut();
        // 监控 Mapper 和 Service 的方法
        pointcut.setPatterns(
                "com.mianmianshi.platform.mapper.*",
                "com.mianmianshi.platform.service.*"
        );
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor();
        advisor.setPointcut(pointcut);
        advisor.setAdvice(interceptor);
        return advisor;
    }

    /**
     * Druid 监控页面 Servlet
     */
    @Bean
    public ServletRegistrationBean<StatViewServlet> druidStatViewServlet() {
        ServletRegistrationBean<StatViewServlet> bean =
                new ServletRegistrationBean<>(new StatViewServlet(), "/druid/*");
        bean.addInitParameter("loginUsername", "admin");
        bean.addInitParameter("loginPassword", "admin123");
        bean.addInitParameter("resetEnable", "false");
        // 仅内网访问
        bean.addInitParameter("allow", "127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16");
        return bean;
    }

    /**
     * Web 关联监控 Filter
     */
    @Bean
    public FilterRegistrationBean<WebStatFilter> druidWebStatFilter() {
        FilterRegistrationBean<WebStatFilter> bean =
                new FilterRegistrationBean<>(new WebStatFilter());
        bean.addUrlPatterns("/*");
        bean.addInitParameter("exclusions",
                "*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*");
        bean.addInitParameter("profileEnable", "true");
        bean.addInitParameter("sessionStatEnable", "true");
        bean.addInitParameter("sessionStatMaxCount", "1000");
        return bean;
    }
}
