package com.liule.ticket.dto;

import lombok.Data;

@Data

public class SeckillFormDTO {

    /**
     * 要抢的票档ID
     */
    private Long tierId;

    /**
     * 购买数量。受两层独立约束：
     * 票档的 max_per_order 是单笔上限（本字段的直接上限，Java 侧前置校验），
     * 场次的 purchase_limit 是每账号在本场次的累计上限（由秒杀 Lua 按账号累计判）。
     */
    private Integer quantity;

}