package com.liule.ticket.config;

import cn.hutool.core.util.StrUtil;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 单机客户端。
 * <p>
 * 原版声明了 redissonClient / redissonClient1 / redissonClient2 三个 Bean、端口硬编码成
 * 6379/6380/6381 —— 那是学"Redisson 联锁/红锁"时的临时写法，后两个地址上根本没有服务，
 * 三个 Bean 都是饿汉式创建，应用启动时直接抛连接异常。这里收敛成一个，地址从配置读。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    /** destroyMethod：不写的话应用关闭时 Netty 线程池还在跑，进程会卡住不退出 */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setPassword(StrUtil.isBlank(password) ? null : password);
        return Redisson.create(config);
    }
}
