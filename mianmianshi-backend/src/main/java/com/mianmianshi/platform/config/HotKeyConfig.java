package com.mianmianshi.platform.config;

import com.jd.platform.hotkey.client.ClientStarter;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * hotkey �?key 发现配置
 *
 * @author <a href="https://github.com/liyupi">程序员鱼�?/a>
 * @from <a href="https://yupi.icu">编程导航</a>
 */
// todo 取消注释开�?HotKey（须先配�?HotKey�?
//@Configuration
//@ConfigurationProperties(prefix = "hotkey")
@Data
public class HotKeyConfig {

    /**
     * Etcd 服务器完整地址
     */
    private String etcdServer = "http://127.0.0.1:2379";

    /**
     * 应用名称
     */
    private String appName = "app";

    /**
     * 本地缓存最大数�?
     */
    private int caffeineSize = 10000;

    /**
     * 批量推�?key 的间隔时�?
     */
    private long pushPeriod = 1000L;

    /**
     * 初始�?hotkey
     */
    @Bean
    public void initHotkey() {
        ClientStarter.Builder builder = new ClientStarter.Builder();
        ClientStarter starter = builder.setAppName(appName)
                .setCaffeineSize(caffeineSize)
                .setPushPeriod(pushPeriod)
                .setEtcdServer(etcdServer)
                .build();
        starter.startPipeline();
    }

}
