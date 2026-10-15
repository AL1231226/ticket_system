package com.liule.ticket;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;


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
