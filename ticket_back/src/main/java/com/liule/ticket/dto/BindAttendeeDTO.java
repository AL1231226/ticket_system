package com.liule.ticket.dto;

import lombok.Data;

import java.util.List;

/**
 * 给订单绑定观演人的请求体。
 */
@Data
public class BindAttendeeDTO {

    /**
     * 观演人 id 列表。
     * <p>
     * 数量和订单的 {@code quantity} 必须一致；列表顺序就是 {@code seq} 的顺序
     * （第 1 个对应第 1 张票）。
     */
    private List<Long> attendeeIds;

}
