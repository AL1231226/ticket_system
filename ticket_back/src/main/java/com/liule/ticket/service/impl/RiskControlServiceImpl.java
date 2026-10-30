package com.liule.ticket.service.impl;

import com.liule.ticket.service.IRiskControlService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class RiskControlServiceImpl implements IRiskControlService {

    private static final DefaultRedisScript<Long> LIMIT_SCRIPT;

    static {
        LIMIT_SCRIPT = new DefaultRedisScript<>();
        LIMIT_SCRIPT.setLocation(new ClassPathResource("limit.lua"));
        LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean check(Long userId, String ip, String deviceId) {
        if (userId == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        // 1. 用户级：5 次/秒
        if (!allow(RedisConstants.LIMIT_USER_KEY + userId, now,
                SystemConstants.RISK_WINDOW_MILLIS, SystemConstants.RISK_USER_LIMIT)) {
            log.warn("抢票风控：用户 {} 超过 {} 次/秒",
                    userId, SystemConstants.RISK_USER_LIMIT);
            return false;
        }

        // 2. IP 级：50 次/秒。取不到 IP 就不做这一层
        if (ip != null && !ip.isBlank()
                && !allow(RedisConstants.LIMIT_IP_KEY + ip, now,
                SystemConstants.RISK_WINDOW_MILLIS, SystemConstants.RISK_IP_LIMIT)) {
            log.warn("抢票风控：IP {} 超过 {} 次/秒",
                    ip, SystemConstants.RISK_IP_LIMIT);
            return false;
        }

        // 3. 设备层：只记录/告警，不影响放行
        recordDevice(userId, deviceId);
        return true;
    }

    /**
     * 执行一次滑动窗口判断。Redis 抖动时放行 —— 限购和库存还有 DB 兜底，
     * 不能因为风控组件故障把所有正常用户挡在门外。
     */
    private boolean allow(String key, long now, long windowMillis, int limit) {
        try {
            Long result = stringRedisTemplate.execute(
                    LIMIT_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),
                    String.valueOf(windowMillis),
                    String.valueOf(limit),
                    UUID.randomUUID().toString());
            return result != null && result == 1L;
        } catch (Exception e) {
            log.error("风控限流脚本执行失败，本次放行：key={}", key, e);
            return true;
        }
    }

    private void recordDevice(Long userId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        String key = RedisConstants.DEVICE_USERS_KEY + deviceId;
        try {
            stringRedisTemplate.opsForSet().add(key, userId.toString());
            stringRedisTemplate.expire(
                    key, SystemConstants.RISK_DEVICE_TTL_SECONDS, TimeUnit.SECONDS);
            Long count = stringRedisTemplate.opsForSet().size(key);
            if (count != null && count > SystemConstants.RISK_DEVICE_ACCOUNT_LIMIT) {
                log.warn("抢票风控：设备 {} 关联了 {} 个账号（阈值 {}），当前用户 {}",
                        deviceId, count, SystemConstants.RISK_DEVICE_ACCOUNT_LIMIT, userId);
            }
        } catch (Exception e) {
            // 设备层只做风险记录，失败不能影响抢票
            log.error("设备账号记录失败：deviceId={}, userId={}", deviceId, userId, e);
        }
    }
}
