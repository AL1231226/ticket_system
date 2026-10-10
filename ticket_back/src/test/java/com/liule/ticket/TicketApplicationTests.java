package com.liule.ticket;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冒烟测试：只验证地基通电了，不碰业务逻辑。
 * <p>
 * 两个测试各管一件事，不能合并：
 * <ul>
 *   <li>{@link #contextLoads()} —— Spring 容器能不能起来。Bean 循环依赖、yaml 写错、
 *       {@code RedissonConfig} 有问题，都会让容器启动就失败，测试根本进不到方法体。</li>
 *   <li>{@link #redisIsReachable()} —— Redis 真的连得上吗。这个必须单独测：Lettuce 是
 *       <b>懒连接</b>，创建 Bean 的时候不建立 TCP 连接，第一次执行命令才连。
 *       所以"容器起来了"不等于"Redis 通了"。</li>
 * </ul>
 */
@SpringBootTest
class TicketApplicationTests {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Test
    void contextLoads() {
        assertThat(stringRedisTemplate).isNotNull();
        assertThat(redissonClient).isNotNull();
    }

    @Test
    void redisIsReachable() {
        stringRedisTemplate.opsForValue().set("smoke:ping", "pong");
        assertThat(stringRedisTemplate.opsForValue().get("smoke:ping")).isEqualTo("pong");
        stringRedisTemplate.delete("smoke:ping");
    }
}
