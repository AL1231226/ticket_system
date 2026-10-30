package com.liule.ticket.dto;

import lombok.Data;

import java.util.List;

/**
 * 发布动态入参。正文与图片至少填一项，具体校验放在 Service 实现里。
 */
@Data
public class PostCreateDTO {

    private String title;
    private String content;

    /** 图片 URL 列表，最多 9 张 */
    private List<String> images;

    private Long showId;
    private Long sessionId;
}
