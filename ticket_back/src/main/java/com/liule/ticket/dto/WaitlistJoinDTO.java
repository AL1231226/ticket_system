package com.liule.ticket.dto;

import lombok.Data;

/**
 * 加入候补的请求体。
 */
@Data
public class WaitlistJoinDTO {

    /**
     * 候补的票档。场次由票档反查，不单独传。
     */
    private Long tierId;

    /**
     * 想候补几张。必须大于 0，且不超过票档的单笔限购。
     */
    private Integer quantity;

}
