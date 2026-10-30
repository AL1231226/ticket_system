package com.liule.ticket.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论展示对象：评论本体 + 评论人昵称/头像。
 * <p>
 * 对外 ID 一律按字符串返回，避免 JS Number 精度丢失。
 */
@Data
public class CommentVO {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long postId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String nickname;
    private String icon;
    private String content;
    private LocalDateTime createTime;
}
