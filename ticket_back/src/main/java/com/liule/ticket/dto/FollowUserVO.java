package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 关注 / 粉丝 / 共同关注列表里的用户卡片。
 */
@Data
public class FollowUserVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String icon;
    private String introduce;
}
