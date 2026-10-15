package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.mapper.TicketTierMapper;
import com.liule.ticket.service.ITicketTierService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

import static com.liule.ticket.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class TicketTierServiceImpl extends ServiceImpl<TicketTierMapper, TicketTier>
        implements ITicketTierService {

    @Override
    public BigDecimal lowestPriceByShowId(Long showId) {
        // 只取 price 一列，按价格升序取第一条。
        // 票档数量少（一场演出通常个位数），没必要为了省这一次查询去写 GROUP BY。
        TicketTier cheapest = getOne(new LambdaQueryWrapper<TicketTier>()
                .select(TicketTier::getPrice)
                .eq(TicketTier::getShowId, showId)
                .eq(TicketTier::getStatus, 1)
                .orderByAsc(TicketTier::getPrice)
                .last("LIMIT 1"));

        return cheapest == null ? null : cheapest.getPrice();
    }
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Long queryStock(Long tierId) {
        String key = SECKILL_STOCK_KEY + tierId;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            return Long.valueOf(cached);
        }
        TicketTier tier = getById(tierId);
        if (tier == null) {
            return null;
        }
        // 无 TTL（见 RedisConstants 上的说明）。Redis 重启丢了这个 key 也不会不一致：
        // 未落库的下单消息跟它一起丢了，DB 里的 stock 也没扣，两边仍然对得上。
        stringRedisTemplate.opsForValue()
                .setIfAbsent(key, String.valueOf(tier.getStock()));
        return tier.getStock().longValue();
    }

    @Override
    public int preloadStock() {
        List<TicketTier> tiers = list(new LambdaQueryWrapper<TicketTier>()
                .eq(TicketTier::getStatus, 1));

        int loaded = 0;
        for (TicketTier tier : tiers) {
            Boolean created = stringRedisTemplate.opsForValue()
                    .setIfAbsent(SECKILL_STOCK_KEY + tier.getId(), String.valueOf(tier.getStock()));
            if (Boolean.TRUE.equals(created)) {
                loaded++;
            }
        }
        return loaded;
    }


}
