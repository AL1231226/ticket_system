package com.liule.ticket.dto;

import lombok.Data;

@Data
public class UserDTO {
    private Long id;
    /** 字段名必须和 tb_user.nickname 一致，否则存进 Redis 的 Hash 里是 nickName 这个 key */
    private String nickname;
    private String icon;

}
