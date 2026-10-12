package com.liule.ticket.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CacheClient 的行为验证：未过期直接返回、逻辑过期返回旧值并异步重建、
 * 未预热不碰库、TTL 带抖动。
 * 测试用固定前缀 cache:test / lock:test，结束后统一清理，不动共享 Redis 里的其他数据。
 */
@SpringBootTest
class CacheClientTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Demo {
        private Long id;
        private String name;
    }

    private static final String KEY = "cache:test:demo:1";
    private static final String LOCK_KEY = "lock:test:demo:1";
    private static final String JITTER_KEY = "cache:test:jitter";

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanUp() {
        stringRedisTemplate.delete(KEY);
        stringRedisTemplate.delete(LOCK_KEY);
        stringRedisTemplate.delete(JITTER_KEY);
    }

    @Test
    void logicalExpireRoundTrip() {
        cacheClient.setWithLogicalExpire(KEY, new Demo(1L, "星野乐队"), Duration.ofMinutes(30));

        AtomicInteger dbHits = new AtomicInteger();
        Supplier<Demo> fallback = () -> {
            dbHits.incrementAndGet();
            return new Demo(1L, "重新查出来的");
        };

        Demo result = cacheClient.queryWithLogicalExpire(
                KEY, LOCK_KEY, Demo.class, fallback, Duration.ofMinutes(30));

        assertThat(result.getName()).isEqualTo("星野乐队");
        assertThat(dbHits.get()).isZero();   // 没过期，不该回源
    }

    @Test
    void expiredEntryReturnsStaleValueAndRebuildsAsync() throws InterruptedException {
        // 直接写一条确定逻辑过期的数据：expireTime 在过去。
        // 不走 setWithLogicalExpire —— 它会给 TTL 加 ±300s 抖动，Duration.ZERO 有一半概率落到未来，
        // 条目就"没过期"，重建永远不会触发。
        RedisData expired = new RedisData();
        expired.setData(new Demo(1L, "旧值"));
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        stringRedisTemplate.opsForValue().set(KEY, cn.hutool.json.JSONUtil.toJsonStr(expired));

        AtomicInteger dbHits = new AtomicInteger();
        Supplier<Demo> fallback = () -> {
            dbHits.incrementAndGet();
            return new Demo(1L, "新值");
        };

        Demo result = cacheClient.queryWithLogicalExpire(
                KEY, LOCK_KEY, Demo.class, fallback, Duration.ofMinutes(30));

        // 关键行为：过期了也返回旧值，不阻塞请求（这正是"逻辑过期"和"互斥锁"的区别）
        assertThat(result.getName()).isEqualTo("旧值");

        // 等异步重建完成
        Thread.sleep(1000);

        assertThat(dbHits.get()).isEqualTo(1);
        RedisData rebuilt = cn.hutool.json.JSONUtil.toBean(
                stringRedisTemplate.opsForValue().get(KEY), RedisData.class);
        assertThat(rebuilt.getExpireTime()).isAfter(LocalDateTime.now());
    }

    @Test
    void missingKeyReturnsNullWithoutTouchingDb() {
        stringRedisTemplate.delete(KEY);

        AtomicInteger dbHits = new AtomicInteger();
        Supplier<Demo> fallback = () -> {
            dbHits.incrementAndGet();
            return new Demo(1L, "不该被调用");
        };

        Demo result = cacheClient.queryWithLogicalExpire(
                KEY, LOCK_KEY, Demo.class, fallback, Duration.ofMinutes(30));

        assertThat(result).isNull();
        // 未预热时直接返回 null，让调用方决定要不要回源 + 写空值缓存
        assertThat(dbHits.get()).isZero();
    }

    @Test
    void ttlHasJitterToAvoidAvalanche() {
        // 同一个 key 写两次，Redis 里剩下的 TTL 不应该完全一样（±5 分钟抖动）
        cacheClient.set(JITTER_KEY, "v", Duration.ofMinutes(30));
        Long first = stringRedisTemplate.getExpire(JITTER_KEY);

        stringRedisTemplate.delete(JITTER_KEY);
        cacheClient.set(JITTER_KEY, "v", Duration.ofMinutes(30));
        Long second = stringRedisTemplate.getExpire(JITTER_KEY);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // 基础 TTL 是 1800 秒，抖动后两者都应落在 [1500, 2100] 区间
        assertThat(first).isBetween(1500L, 2100L);
        assertThat(second).isBetween(1500L, 2100L);
    }
}
