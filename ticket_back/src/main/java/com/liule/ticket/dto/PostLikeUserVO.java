package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 点赞用户（Top5 展示用）。
 */
@Data
public class PostLikeUserVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String icon;
}
