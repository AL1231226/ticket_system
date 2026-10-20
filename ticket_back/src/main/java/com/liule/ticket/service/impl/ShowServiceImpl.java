package com.liule.ticket.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.ShowDetailVO;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.mapper.ShowMapper;
import com.liule.ticket.service.IShowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.utils.CacheClient;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.liule.ticket.utils.RedisConstants.CACHE_SHOW_KEY;

@Service
public class ShowServiceImpl extends ServiceImpl<ShowMapper, Show> implements IShowService {

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private ITicketTierService ticketTierService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result listByCategory(Long categoryId, String city, Integer page) {
        LambdaQueryWrapper<Show> wrapper = new LambdaQueryWrapper<Show>()
                .eq(Show::getStatus, 1)
                .eq(Show::getCategoryId, categoryId)
                .eq(StrUtil.isNotBlank(city), Show::getCity, city)
                .orderByAsc(Show::getStartDate);

        return pageQuery(wrapper, page);
    }

    @Override
    public Result listByVenue(Long venueId, Integer page) {
        LambdaQueryWrapper<Show> wrapper = new LambdaQueryWrapper<Show>()
                .eq(Show::getStatus, 1)
                .eq(Show::getVenueId, venueId)
                .orderByAsc(Show::getStartDate);

        return pageQuery(wrapper, page);
    }

    @Override
    public Result queryDetailById(Long id) {
        String cacheKey = CACHE_SHOW_KEY + id;
        String nullKey = RedisConstants.CACHE_NULL_SHOW_KEY + id;

        // 第一层：空值缓存（防穿透）。
        // 查一个不存在的演出 id，如果不做这层，每次请求都会打到数据库。
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(nullKey))) {
            return Result.fail("演出不存在");
        }

        // 第二层：逻辑过期缓存（防击穿）。
        // 缓存命中且未过期就直接返回；已过期则异步重建，本次仍拿到旧值，不阻塞。
        ShowDetailVO detail = cacheClient.queryWithLogicalExpire(
                cacheKey,
                RedisConstants.LOCK_SHOW_KEY + id,
                ShowDetailVO.class,
                () -> loadFromDb(id),
                Duration.ofMinutes(RedisConstants.CACHE_SHOW_TTL));

        if (detail == null) {
            // 第三层：缓存里没有（首次访问，或还没预热）——同步回源。
            // 注意这层没有互斥锁，冷启动时并发请求会一起打库；
            // 详情属于读多写少，首次访问后就会写进缓存，暂时接受。
            detail = loadFromDb(id);
            if (detail == null) {
                // 演出不存在或已下架：写空值缓存，后续请求由第一层拦下
                stringRedisTemplate.opsForValue()
                        .set(nullKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("演出不存在");
            }
            cacheClient.setWithLogicalExpire(cacheKey, detail,
                    Duration.ofSeconds(RedisConstants.CACHE_SHOW_TTL));
        }

        return Result.ok(detail);
    }

    /**
     * 从数据库装配详情。
     * 返回 null 表示演出不存在或已下架 —— 调用方据此写空值缓存。
     */
    private ShowDetailVO loadFromDb(Long id) {
        Show show = getById(id);
        if (show == null || Integer.valueOf(0).equals(show.getStatus())) {
            return null;
        }
        // 查询该演出所有场次及对应的票价信息
        List<ShowSession> sessions = showSessionService.listWithTiersByShowId(id);
        // 查询该演出最低票价
        show.setMinPrice(ticketTierService.lowestPriceByShowId(id));
        return new ShowDetailVO(show, sessions);
    }

    /**
     * 两个列表接口共用的分页查询。
     * page 为空或小于 1 时按第一页算 —— 调用方可能不传这个参数。
     */
    private Result pageQuery(LambdaQueryWrapper<Show> wrapper, Integer page) {
        long current = (page == null || page < 1) ? 1 : page;
        Page<Show> p = page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE), wrapper);
        fillMinPrice(p.getRecords());
        return Result.ok(p.getRecords(), p.getTotal());
    }

    /**
     * 列表页批量补最低票价。
     * <p>
     * 列表接口只返回 Show，不返回票档；首页要显示「¥xx 起」，
     * 这里按本页的演出 id 一次查回所有上架票档，取最低价，避免前端逐条查详情。
     */
    private void fillMinPrice(List<Show> shows) {
        if (shows == null || shows.isEmpty()) {
            return;
        }
        List<Long> showIds = shows.stream().map(Show::getId).toList();
        Map<Long, BigDecimal> minPrices = new HashMap<>();
        for (TicketTier tier : ticketTierService.list(
                new LambdaQueryWrapper<TicketTier>()
                        .select(TicketTier::getShowId, TicketTier::getPrice)
                        .in(TicketTier::getShowId, showIds)
                        .eq(TicketTier::getStatus, 1))) {
            minPrices.merge(tier.getShowId(), tier.getPrice(), BigDecimal::min);
        }
        shows.forEach(s -> s.setMinPrice(minPrices.get(s.getId())));
    }

}
