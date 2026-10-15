package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.entity.ShowSession;

import java.util.List;

public interface IShowSessionService extends IService<ShowSession> {

    /**
     * 查某个演出下的所有场次，每个场次带上自己的票档列表。
     * 票档只取上架的（tb_ticket_tier.status = 1）。
     */
    List<ShowSession> listWithTiersByShowId(Long showId);
}
