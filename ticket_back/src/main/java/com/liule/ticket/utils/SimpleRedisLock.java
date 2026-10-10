package com.liule.ticket.utils;



import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements Ilock {
    private StringRedisTemplate stringRedisTemplate;
    private  String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String prefix = "lock:";
    private static final String id_prefix= UUID.randomUUID().toString()+"-";
    @Override
    public boolean tryLock(long timeout) {
        //获取线程id
        String threadId = id_prefix + Thread.currentThread().getId();
        // 尝试获取锁
        Boolean isLocked = stringRedisTemplate.opsForValue().setIfAbsent(prefix + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(isLocked);
    }
    private static final DefaultRedisScript<Long> script;
    static {
        script=new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("unlock.lua"));
        script.setResultType(Long.class);
    }
    @Override
    public void unlock() {
       stringRedisTemplate.execute(script, Collections.singletonList(prefix + name), id_prefix + Thread.currentThread().getId());
    }
    /*public void unlock() {
        //获取线程id
        String threadId = id_prefix + Thread.currentThread().getId();
        String currentValue = stringRedisTemplate.opsForValue().get(prefix + name);
        if (threadId.equals(currentValue)) {
            stringRedisTemplate.delete(prefix + name);
        }}
*/


}
