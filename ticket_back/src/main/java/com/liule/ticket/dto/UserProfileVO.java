package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

/**
 * 公开个人主页：只暴露展示字段，不含手机号/密码/生日。
 */
@Data
public class UserProfileVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String icon;
    private String introduce;
    private String city;
    private Integer gender;
    private Integer level;
    private Integer showCount;
    private Integer fans;
    private Integer followee;

    /** 有效动态数（status=0） */
    private Long posts;

    /** 当前登录用户是否关注了 TA；游客/自己恒 false */
    private Boolean isFollowing;
}
