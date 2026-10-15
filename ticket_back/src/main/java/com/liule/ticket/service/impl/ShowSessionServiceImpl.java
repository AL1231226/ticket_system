package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.mapper.ShowSessionMapper;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketTierService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ShowSessionServiceImpl extends ServiceImpl<ShowSessionMapper, ShowSession>
        implements IShowSessionService {

    @Resource
    private ITicketTierService ticketTierService;

    @Override
    public List<ShowSession> listWithTiersByShowId(Long showId) {
        List<ShowSession> sessions = list(new LambdaQueryWrapper<ShowSession>()
                .eq(ShowSession::getShowId, showId)
                .orderByAsc(ShowSession::getSessionTime));

        if (sessions.isEmpty()) {
            return sessions;
        }

        // 一次把所有票档查回来再按 sessionId 分组，而不是每个场次查一次。
        // 演出详情是热点数据，N+1 在缓存击穿时会被放大。
        List<Long> sessionIds = sessions.stream().map(ShowSession::getId).toList();
        Map<Long, List<TicketTier>> tiersBySession = ticketTierService.list(
                        new LambdaQueryWrapper<TicketTier>()
                                .in(TicketTier::getSessionId, sessionIds)
                                .eq(TicketTier::getStatus, 1)
                                .orderByAsc(TicketTier::getPrice))
                .stream()
                .collect(Collectors.groupingBy(TicketTier::getSessionId));

        sessions.forEach(s -> s.setTiers(
                tiersBySession.getOrDefault(s.getId(), Collections.emptyList())));

        return sessions;
    }
}
