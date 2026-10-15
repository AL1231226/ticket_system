package com.liule.ticket.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisIdWorker 的行为验证：单调递增、位结构正确、并发下唯一。
 * 测试用固定前缀 test，结束后清理 icr:test:* 键，不动共享 Redis 里的其他数据。
 */
@SpringBootTest
class RedisIdWorkerTest {

    private static final String PREFIX = "test";

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUp() {
        Set<String> keys = stringRedisTemplate.keys("icr:" + PREFIX + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    @Test
    void idIncreasesMonotonically() {
        long first = redisIdWorker.create_ID(PREFIX);
        long second = redisIdWorker.create_ID(PREFIX);
        long third = redisIdWorker.create_ID(PREFIX);

        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    void idEncodesTimestampAndDailySequence() {
        long before = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - RedisIdWorker.START_time;
        long id = redisIdWorker.create_ID(PREFIX);
        long after = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) - RedisIdWorker.START_time;

        long timestamp = id >>> 32;       // 高 32 位：当前秒 − 起始秒
        long sequence = id & 0xFFFFFFFFL; // 低 32 位：当天自增序列

        assertThat(timestamp).isBetween(before, after);
        assertThat(sequence).isPositive();
    }

    @Test
    void idIsUniqueUnderConcurrency() throws InterruptedException {
        int threadCount = 100;
        int perThread = 100;

        Set<Long> ids = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(); // 等所有线程就位，一起发车，制造真实竞争
                        for (int j = 0; j < perThread; j++) {
                            ids.add(redisIdWorker.create_ID(PREFIX));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // 10000 个 ID 必须一个不重
        assertThat(ids).hasSize(threadCount * perThread);
    }
}
