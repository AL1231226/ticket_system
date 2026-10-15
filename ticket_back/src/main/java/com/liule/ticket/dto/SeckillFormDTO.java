package com.liule.ticket.dto;

import lombok.Data;

@Data

public class SeckillFormDTO {

    /**
     * 要抢的票档ID
     */
    private Long tierId;

    /**
     * 购买数量。上限由票档的 purchase_limit 决定，另受整场次已购数约束
     */
    private Integer quantity;

}