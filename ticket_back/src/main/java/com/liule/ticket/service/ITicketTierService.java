package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.entity.TicketTier;

import java.math.BigDecimal;

public interface ITicketTierService extends IService<TicketTier> {

    /**
     * 某个演出所有场次里的最低票价，用于显示"¥xxx 起"。
     * 只算上架票档；没有任何上架票档时返回 null。
     */
    BigDecimal lowestPriceByShowId(Long showId);

    Long queryStock(Long tierId);

    int preloadStock();
}
