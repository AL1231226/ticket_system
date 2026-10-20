package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 观演人列表的返回项。
 * <p>
 * 证件号是脱敏的（{@code 110101********1234}）—— 证件号属于敏感个人信息，
 * 列表接口没有理由把完整号码回给前端。
 */
@Data
@AllArgsConstructor
public class AttendeeVO {

    private Long id;

    private String realName;

    /**
     * 脱敏后的证件号
     */
    private String idCard;

    private Integer idCardType;

    /**
     * 0未认证 1已认证 2认证失败
     */
    private Integer verifyStatus;

}
