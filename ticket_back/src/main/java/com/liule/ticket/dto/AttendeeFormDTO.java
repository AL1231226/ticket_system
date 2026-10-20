package com.liule.ticket.dto;

import lombok.Data;

/**
 * 新增观演人的请求体。
 */
@Data
public class AttendeeFormDTO {

    /**
     * 观演人姓名，必须和证件一致
     */
    private String realName;

    /**
     * 证件号
     */
    private String idCard;

    /**
     * 证件类型：1 身份证 / 2 港澳 / 3 台湾 / 4 护照。
     * 不传默认 1；只有身份证会做 18 位格式校验。
     */
    private Integer idCardType;

}
