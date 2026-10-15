package com.liule.ticket.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liule.ticket.dto.Result;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.liule.ticket.utils.RedisConstants.*;
@Component
public class RedisClient {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;
    public  void Redis_set_ttl(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }
    public  void Redis_set_logical_ttl(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    public <R,ID> R queryById_chuntou(String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID,R> function) {
        //缓存中查询商户
        String key = keyPrefix + id;
        //key不存在返回null,key存在，可能为空字符
        String Json= stringRedisTemplate.opsForValue().get(key);
        //缓存中存在,返回商户
        if(StrUtil.isNotBlank(Json)){
            R r = JSONUtil.toBean(Json, type);
            return r;
        }
        if(Json != null){
            return null;
        }
        //缓存中不存在,查询数据库
        R r = function.apply(id);
        //数据库中不存在,报错
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES );
            return null;
        }
        //数据库中存在,写入缓存
        this.Redis_set_ttl(key,r,time,unit);

        return r;

    }
    private  static  final ExecutorService ex= Executors.newFixedThreadPool(10);
    public <R, ID> R queryById_expiretime(String keyPrefix, ID id, Class<R> type, Long time, TimeUnit unit, Function<ID, R> function) {

        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //缓存未过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        //缓存已过期
        if (huoqu_lock(LOCK_SHOW_KEY + id)) {
            RedisData redisData_q = JSONUtil.toBean(stringRedisTemplate.opsForValue().get(key), RedisData.class);
            if (redisData_q.getExpireTime().isAfter(LocalDateTime.now())) {
                return JSONUtil.toBean((JSONObject) redisData_q.getData(), type);
            }
            ex.submit(() -> {
                try {
                    R r1 = function.apply(id);
                    this.Redis_set_logical_ttl(key, r1, time, unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    shanchu_lock(LOCK_SHOW_KEY + id);
                }
            });

        }


        return r;

    }
    public boolean huoqu_lock(String key) {

        boolean flag =stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOW_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue( flag);

    }
    public void shanchu_lock(String key) {
        stringRedisTemplate.delete(key);
    }


}
