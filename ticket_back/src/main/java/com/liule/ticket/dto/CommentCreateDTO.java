package com.liule.ticket.dto;

import lombok.Data;

/**
 * 发评论请求：v1 只有一级评论，parentId / answerId 由服务端固定为 0。
 */
@Data
public class CommentCreateDTO {

    private Long postId;

    private String content;
}
