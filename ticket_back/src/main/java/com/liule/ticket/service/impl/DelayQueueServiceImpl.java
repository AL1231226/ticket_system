package com.liule.ticket.service.impl;

import com.liule.ticket.service.IDelayQueueService;
import com.liule.ticket.utils.RedisConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DelayQueueServiceImpl implements IDelayQueueService {

    private static final String DELAY_KEY = RedisConstants.DELAY_ORDER_PAY_KEY;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void add(Long orderId, LocalDateTime expireTime) {
        try {
            long score = expireTime.atZone(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
            stringRedisTemplate.opsForZSet().add(DELAY_KEY, orderId.toString(), score);
        } catch (Exception e) {
            // 队列只负责「准时」，DB 的 30 秒扫表才是保底。Redis 抖动不能反过来
            // 把已经落库的订单回滚掉，所以这里只记日志。
            log.warn("订单 {} 加入支付超时延迟队列失败，等待扫表兜底", orderId, e);
        }
    }

    @Override
    public void remove(Long orderId) {
        try {
            stringRedisTemplate.opsForZSet().remove(DELAY_KEY, orderId.toString());
        } catch (Exception e) {
            // 残留一条到期记录不会重复回补：到期后 invalidateOrder 的
            // WHERE status = 0 会失败，然后由任务再删一次。
            log.warn("订单 {} 从支付超时延迟队列移除失败，到期后由状态守卫兜底", orderId, e);
        }
    }

    @Override
    public List<Long> findDue(long nowMillis, int limit) {
        try {
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .rangeByScore(DELAY_KEY, Double.NEGATIVE_INFINITY, nowMillis, 0, limit);
            if (members == null || members.isEmpty()) {
                return Collections.emptyList();
            }
            List<Long> orderIds = new ArrayList<>(members.size());
            for (String member : members) {
                try {
                    orderIds.add(Long.valueOf(member));
                } catch (NumberFormatException e) {
                    log.warn("延迟队列里出现非订单号成员：{}，已跳过", member);
                }
            }
            return orderIds;
        } catch (Exception e) {
            // 读失败也不能让定时任务挂掉；扫表会兜底。
            log.error("读取支付超时延迟队列失败，等待扫表兜底", e);
            return Collections.emptyList();
        }
    }
}
