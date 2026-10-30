package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.dto.WaitlistJoinDTO;
import com.liule.ticket.dto.WaitlistVO;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.entity.Waitlist;
import com.liule.ticket.mapper.UserPurchaseMapper;
import com.liule.ticket.mapper.WaitlistMapper;
import com.liule.ticket.service.IShowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.service.IWaitlistService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WaitlistServiceImpl extends ServiceImpl<WaitlistMapper, Waitlist>
        implements IWaitlistService {

    @Resource
    private ITicketTierService ticketTierService;

    @Resource
    private IShowSessionService showSessionService;

    @Resource
    private IShowService showService;

    @Resource
    private UserPurchaseMapper userPurchaseMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result join(WaitlistJoinDTO form) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (form == null || form.getTierId() == null) {
            return Result.fail("请选择票档");
        }
        Integer quantity = form.getQuantity();
        if (quantity == null || quantity <= 0) {
            return Result.fail("候补数量不正确");
        }

        TicketTier tier = ticketTierService.getById(form.getTierId());
        if (tier == null || !Integer.valueOf(1).equals(tier.getStatus())) {
            return Result.fail("票档不存在或已下架");
        }
        if (quantity > tier.getMaxPerOrder()) {
            return Result.fail("每单限购 " + tier.getMaxPerOrder() + " 张");
        }

        ShowSession session = showSessionService.getById(tier.getSessionId());
        if (session == null) {
            return Result.fail("场次不存在");
        }
        // 允许 1 预售中 / 2 已开票 / 3 已售罄 —— 已售罄正是候补的主要场景
        if (!Integer.valueOf(1).equals(session.getStatus())
                && !Integer.valueOf(2).equals(session.getStatus())
                && !Integer.valueOf(3).equals(session.getStatus())) {
            return Result.fail("本场次当前不可候补");
        }
        LocalDateTime now = LocalDateTime.now();
        if (session.getOnSaleTime() != null && session.getOnSaleTime().isAfter(now)) {
            return Result.fail("还未到开票时间");
        }
        if (session.getSessionTime() != null && session.getSessionTime().isBefore(now)) {
            return Result.fail("本场次已结束");
        }

        // 限购预检：已购 + 候补数量不能超过场次累计上限。
        // 不检查的话，排进去也永远补不上，还会成为队首堵住后面的人。
        Integer bought = userPurchaseMapper.getQuantity(user.getId(), session.getId());
        int already = bought == null ? 0 : bought;
        if (session.getPurchaseLimit() != null
                && already + quantity > session.getPurchaseLimit()) {
            return Result.fail("达到本场次限购上限");
        }

        Waitlist existing = getOne(new LambdaQueryWrapper<Waitlist>()
                .eq(Waitlist::getUserId, user.getId())
                .eq(Waitlist::getTierId, tier.getId()));

        String key = RedisConstants.WAITLIST_QUEUE_KEY + tier.getId();
        String member = user.getId().toString();

        if (existing != null) {
            if (Integer.valueOf(0).equals(existing.getStatus())) {
                Long rank = stringRedisTemplate.opsForZSet().rank(key, member);
                if (rank != null) {
                    return Result.fail("已在候补中");
                }
                // DB 说排队中但 ZSet 没有（Redis 丢数据 / 上次写失败）：
                // 按原入队时间补写，不把用户挤到队尾。
                ensureQueued(existing);
                return Result.ok(positionData(tier.getId(), existing.getJoinTime()));
            }
            if (Integer.valueOf(1).equals(existing.getStatus())) {
                return Result.fail("已获得补位，请先支付或取消");
            }
            if (Integer.valueOf(2).equals(existing.getStatus())) {
                return Result.fail("已成功购票，无需重复候补");
            }

            // 3 已过期 / 4 已取消：复用原行，清掉补位信息，重新从队尾开始
            boolean reset = update()
                    .set("session_id", session.getId())
                    .set("quantity", quantity)
                    .set("status", 0)
                    .set("order_id", 0L)
                    .set("notify_time", null)
                    .set("expire_time", null)
                    .set("join_time", now)
                    .eq("id", existing.getId())
                    .eq("status", existing.getStatus())
                    .update();
            if (!reset) {
                return Result.fail("候补状态已变化，请重试");
            }
        } else {
            Waitlist row = new Waitlist()
                    .setUserId(user.getId())
                    .setTierId(tier.getId())
                    .setSessionId(session.getId())
                    .setQuantity(quantity)
                    .setStatus(0)
                    .setOrderId(0L)
                    .setJoinTime(now);
            try {
                save(row);
            } catch (DuplicateKeyException e) {
                // uk_user_tier：并发 join 时另一个请求已经插入
                return Result.fail("已在候补中");
            }
        }

        stringRedisTemplate.opsForZSet().add(key, member, now.atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli());
        return Result.ok(positionData(tier.getId(), now));
    }

    @Override
    public Result listMine() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        List<Waitlist> rows = list(new LambdaQueryWrapper<Waitlist>()
                .eq(Waitlist::getUserId, user.getId())
                .orderByDesc(Waitlist::getJoinTime)
                .orderByDesc(Waitlist::getId));
        return Result.ok(toVOList(rows));
    }

    @Override
    public Result position(Long tierId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (tierId == null) {
            return Result.fail("请选择票档");
        }

        Waitlist row = getOne(new LambdaQueryWrapper<Waitlist>()
                .eq(Waitlist::getUserId, user.getId())
                .eq(Waitlist::getTierId, tierId));
        if (row == null || !Integer.valueOf(0).equals(row.getStatus())) {
            return Result.fail("当前不在候补中");
        }

        // Redis 重启丢队列时按 join_time 补写，位置查询不能把用户卡死
        ensureQueued(row);
        return Result.ok(positionData(tierId, row.getJoinTime()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result quit(Long tierId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (tierId == null) {
            return Result.fail("请选择票档");
        }

        Waitlist row = getOne(new LambdaQueryWrapper<Waitlist>()
                .eq(Waitlist::getUserId, user.getId())
                .eq(Waitlist::getTierId, tierId));
        if (row == null) {
            return Result.fail("当前不在候补中");
        }
        if (Integer.valueOf(1).equals(row.getStatus())) {
            return Result.fail("已获得补位，请先取消补位订单");
        }
        if (!Integer.valueOf(0).equals(row.getStatus())) {
            return Result.fail("当前不在候补中");
        }

        // 条件更新：并发退出/被分配时只会成功一次
        boolean updated = update()
                .set("status", 4)
                .eq("id", row.getId())
                .eq("status", 0)
                .update();
        if (!updated) {
            return Result.fail("候补状态已变化，请刷新");
        }

        stringRedisTemplate.opsForZSet().remove(
                RedisConstants.WAITLIST_QUEUE_KEY + tierId, user.getId().toString());
        return Result.ok();
    }

    @Override
    public boolean markNotified(Long waitlistId, Long orderId,
                                LocalDateTime notifyTime, LocalDateTime expireTime) {
        return update()
                .set("status", 1)
                .set("order_id", orderId)
                .set("notify_time", notifyTime)
                .set("expire_time", expireTime)
                .eq("id", waitlistId)
                .eq("status", 0)
                .update();
    }

    @Override
    public boolean markStatusById(Long waitlistId, int expectStatus, int targetStatus) {
        return update()
                .set("status", targetStatus)
                .eq("id", waitlistId)
                .eq("status", expectStatus)
                .update();
    }

    @Override
    public int syncByOrderId(Long orderId, int expectStatus, int targetStatus) {
        return baseMapper.update(null, new LambdaUpdateWrapper<Waitlist>()
                .set(Waitlist::getStatus, targetStatus)
                .eq(Waitlist::getOrderId, orderId)
                .eq(Waitlist::getStatus, expectStatus));
    }

    /**
     * 保证排队中的用户在 ZSet 里有一席。缺失时按 {@code join_time} 补写；
     * 已有的不动，避免正常查询把用户重新排到队尾。
     */
    private void ensureQueued(Waitlist row) {
        String key = RedisConstants.WAITLIST_QUEUE_KEY + row.getTierId();
        String member = row.getUserId().toString();
        Long rank = stringRedisTemplate.opsForZSet().rank(key, member);
        if (rank != null) {
            return;
        }
        long score = row.getJoinTime() == null
                ? System.currentTimeMillis()
                : row.getJoinTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        stringRedisTemplate.opsForZSet().add(key, member, score);
        log.warn("候补行 {} 在 ZSet 中缺失，已按 join_time 补写", row.getId());
    }

    /**
     * 位置响应：position 从 1 开始；用户不在 ZSet 时 position 为 null。
     */
    private Map<String, Object> positionData(Long tierId, LocalDateTime joinTime) {
        String key = RedisConstants.WAITLIST_QUEUE_KEY + tierId;
        String member = UserHolder.getUser().getId().toString();
        Long rank = stringRedisTemplate.opsForZSet().rank(key, member);
        Long total = stringRedisTemplate.opsForZSet().zCard(key);

        Map<String, Object> data = new HashMap<>();
        data.put("tierId", tierId);
        data.put("position", rank == null ? null : rank + 1);
        data.put("total", total == null ? 0 : total);
        data.put("joinTime", joinTime);
        return data;
    }

    /**
     * 批量装配候补展示字段：一次查齐演出、场次、票档，避免逐行回源。
     */
    private List<WaitlistVO> toVOList(List<Waitlist> rows) {
        if (rows == null || rows.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> sessionIds = rows.stream()
                .map(Waitlist::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> tierIds = rows.stream()
                .map(Waitlist::getTierId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, ShowSession> sessionMap = sessionIds.isEmpty() ? new HashMap<>()
                : showSessionService.listByIds(sessionIds).stream()
                        .collect(Collectors.toMap(ShowSession::getId, Function.identity()));
        Map<Long, TicketTier> tierMap = tierIds.isEmpty() ? new HashMap<>()
                : ticketTierService.listByIds(tierIds).stream()
                        .collect(Collectors.toMap(TicketTier::getId, Function.identity()));

        List<Long> showIds = tierMap.values().stream()
                .map(TicketTier::getShowId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Show> showMap = showIds.isEmpty() ? new HashMap<>()
                : showService.listByIds(showIds).stream()
                        .collect(Collectors.toMap(Show::getId, Function.identity()));

        String member = UserHolder.getUser().getId().toString();
        List<WaitlistVO> result = new ArrayList<>(rows.size());
        for (Waitlist row : rows) {
            WaitlistVO vo = new WaitlistVO();
            vo.setWaitlist(row);

            ShowSession session = sessionMap.get(row.getSessionId());
            if (session != null) {
                vo.setSessionName(session.getSessionName());
                vo.setSessionTime(session.getSessionTime());
            }

            TicketTier tier = tierMap.get(row.getTierId());
            if (tier != null) {
                vo.setTierName(tier.getName());
                vo.setSeatArea(tier.getSeatArea());
                vo.setPrice(tier.getPrice());
                Show show = showMap.get(tier.getShowId());
                if (show != null) {
                    vo.setShowName(show.getName());
                    vo.setPoster(show.getPoster());
                }
            }

            if (Integer.valueOf(0).equals(row.getStatus())) {
                String key = RedisConstants.WAITLIST_QUEUE_KEY + row.getTierId();
                Long rank = stringRedisTemplate.opsForZSet().rank(key, member);
                Long total = stringRedisTemplate.opsForZSet().zCard(key);
                vo.setPosition(rank == null ? null : rank.intValue() + 1);
                vo.setTotal(total == null ? 0 : total.intValue());
            }

            result.add(vo);
        }
        return result;
    }
}
