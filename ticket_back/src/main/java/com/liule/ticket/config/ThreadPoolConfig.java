package com.liule.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存重建线程池。
 * <p>
 * 只给"逻辑过期重建"用。原版用 {@code Executors.newFixedThreadPool(10)} 的静态字段，
 * 问题有两个：一是无界队列会一直堆积任务直到 OOM，二是静态字段不受 Spring 管理、关不掉。
 */
@Configuration
public class ThreadPoolConfig {

    private static final AtomicInteger POOL_SEQ = new AtomicInteger(1);

    @Bean(name = "cacheRebuildExecutor")
    public ThreadPoolExecutor cacheRebuildExecutor() {
        int coreSize = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                coreSize,                       // 核心线程数
                coreSize * 2,                   // 最大线程数
                60L, TimeUnit.SECONDS,          // 空闲回收时间
                new LinkedBlockingQueue<>(200), // 有界队列，满了触发拒绝策略
                r -> new Thread(r, "cache-rebuild-" + POOL_SEQ.getAndIncrement()),
                // 队列满了以后由调用线程自己执行：宁可拖慢这一个请求，也不丢重建任务
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
