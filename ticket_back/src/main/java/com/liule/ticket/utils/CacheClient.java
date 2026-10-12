package com.liule.ticket.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 缓存工具：只负责 Redis 读写与缓存策略，不认识任何业务实体。
 * 回源逻辑由调用方以 {@link Supplier} 传进来。
 * <p>
 * 对应原版的 RedisClient，改动如下：
 * 1. 拼音方法名 → 英文（queryById_chuntou → queryWithLogicalExpire 等）
 * 2. 锁 key 前缀不再写死 LOCK_SHOP_KEY，改为参数传入
 * 3. 线程池从静态字段改为注入 Spring 管理的 Bean
 * 4. 补上 TTL 随机抖动（防雪崩），原版完全没做
 */
@Slf4j
@Component
public class CacheClient {

    /** 雪崩防护：TTL 抖动幅度（秒），实际 TTL 落在 [ttl-300, ttl+300] */
    private static final long TTL_JITTER_SECONDS = 300L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ThreadPoolExecutor cacheRebuildExecutor;

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       @Qualifier("cacheRebuildExecutor") ThreadPoolExecutor cacheRebuildExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
    }

    /**
     * 写普通 JSON 缓存，TTL 带随机抖动。
     * 抖动是为了防雪崩：如果一批 key 同时写入、TTL 又完全相同，
     * 它们会在同一秒集体失效，请求洪峰直接砸到数据库。
     */
    public void set(String key, Object value, Duration ttl) {
        stringRedisTemplate.opsForValue()
                .set(key, JSONUtil.toJsonStr(value), jitter(ttl));
    }

    /**
     * 写逻辑过期缓存：数据不过期，过期时间存在 value 内部。
     */
    public void setWithLogicalExpire(String key, Object value, Duration ttl) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plus(jitter(ttl)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 读逻辑过期缓存。
     * <ul>
     *   <li>key 不存在 → 返回 {@code null}（由调用方决定是否回源、是否写空值缓存）</li>
     *   <li>未过期 → 直接返回</li>
     *   <li>已过期 → 抢到锁的线程提交异步重建任务，<b>所有线程都立刻拿到旧值返回</b></li>
     * </ul>
     * 与"互斥锁"方案的区别：互斥锁是让没抢到锁的线程等待，保证数据新鲜但会阻塞；
     * 逻辑过期是永远不阻塞，代价是短时间内读到旧数据。票务详情页读多写少、能容忍秒级旧数据，选逻辑过期。
     *
     * @param key        缓存 key（完整 key，含前缀）
     * @param lockKey    重建时的互斥锁 key
     * @param type       返回类型
     * @param dbFallback 缓存失效时的回源逻辑
     * @param ttl        逻辑过期时长（实际会加抖动）
     */
    public <R> R queryWithLogicalExpire(String key, String lockKey, Class<R> type,
                                        Supplier<R> dbFallback, Duration ttl) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R value = BeanUtil.toBean(redisData.getData(), type);

        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return value;
        }

        // 已逻辑过期：只有抢到锁的线程去重建，其余线程拿着旧值直接返回
        if (tryLock(lockKey)) {
            cacheRebuildExecutor.submit(() -> rebuild(key, lockKey, type, dbFallback, ttl));
        }
        return value;
    }

    /** 异步重建。整个过程跑在线程池里，不能抛异常出去，否则线程池会静默吞掉。 */
    private <R> void rebuild(String key, String lockKey, Class<R> type,
                             Supplier<R> dbFallback, Duration ttl) {
        try {
            // 双重检查：从"读到过期"到"抢到锁"之间，可能已经有别的线程重建完了
            String latest = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(latest)) {
                RedisData latestData = JSONUtil.toBean(latest, RedisData.class);
                if (latestData.getExpireTime().isAfter(LocalDateTime.now())) {
                    return;
                }
            }

            R fresh = dbFallback.get();
            if (fresh == null) {
                // 数据被删了：清掉缓存，交给调用方的空值缓存策略接管
                stringRedisTemplate.delete(key);
                log.warn("缓存重建时数据已不存在，key={}", key);
                return;
            }
            setWithLogicalExpire(key, fresh, ttl);
        } catch (Exception e) {
            log.error("缓存重建失败，key={}", key, e);
        } finally {
            // 注意：这里删锁有个经典缺陷 —— 如果重建耗时超过锁的 TTL，
            // 锁早已自动释放并被别的线程重新持有，此时删的是别人的锁。
            // Plan 2 引入 Redisson 后用它解决（持有者校验）。
            stringRedisTemplate.delete(lockKey);
        }
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", RedisConstants.LOCK_SHOW_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /** 给 TTL 加 ±300 秒的随机抖动 */
    private Duration jitter(Duration ttl) {
        return ttl.plusSeconds(ThreadLocalRandom.current().nextLong(-TTL_JITTER_SECONDS, TTL_JITTER_SECONDS));
    }
}
