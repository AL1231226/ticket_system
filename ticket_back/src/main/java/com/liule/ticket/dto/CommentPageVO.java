package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 一页评论列表：评论 VO + 下一页游标 + 是否还有更多。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentPageVO {

    private List<CommentVO> comments;
    private FeedCursor nextCursor;
    private boolean hasMore;

    public static CommentPageVO empty() {
        return new CommentPageVO(Collections.emptyList(), null, false);
    }
}
