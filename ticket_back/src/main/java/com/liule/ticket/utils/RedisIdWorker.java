package com.liule.ticket.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //开始时间戳，2021-01-01
    public static final Long START_time=1609459200L;
    public static final int COUNT_BITS = 32;
    public Long create_ID(String frekey){
        //获取当前时间戳
        LocalDateTime now = LocalDateTime.now();
        long now_time= now.toEpochSecond(ZoneOffset.UTC);
        //时间差号
        long timestamp = now_time - START_time;


        //reids自增的序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long increment = stringRedisTemplate.opsForValue().increment("icr:"+frekey+":"+date);

        //拼接并返回
        return timestamp<<COUNT_BITS | increment;

    }

    public static void main(String[] args) {
        java.time.LocalDateTime beginTime = java.time.LocalDateTime.of(2021, 1, 1, 0, 0, 0);
        long beginSeconds = beginTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("2021-01-01 00:00:00 对应的秒数: " + beginSeconds);

    }
}
