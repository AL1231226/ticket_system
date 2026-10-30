package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 动态展示对象：动态本体 + 作者 + 关联演出卡片。
 * <p>
 * 表里只存 userId / showId / 逗号分隔的 images，列表和详情都需要展示字段，
 * 统一由 PostAssembler 批量装配，避免 N+1。
 * <p>
 * 对外 ID 一律按字符串返回，避免 JS Number 精度丢失。
 */
@Data
public class PostVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String icon;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long showId;

    private String showName;
    private String showPoster;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    private String title;
    private String content;
    private List<String> images;

    private Integer liked;
    private Integer comments;
    //该篇帖子是否被当前查看者点赞
    private Boolean likedByMe;
    private LocalDateTime createTime;
}
