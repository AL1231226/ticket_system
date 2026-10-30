package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注/取关结果：当前关系 + 目标用户最新粉丝数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowResultVO {

    private Boolean following;

    private Integer fans;
}
