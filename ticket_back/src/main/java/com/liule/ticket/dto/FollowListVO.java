package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 一页关注 / 粉丝列表：用户卡片 + 下一页游标 + 是否还有更多。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowListVO {

    private List<FollowUserVO> users;
    private FeedCursor nextCursor;
    private boolean hasMore;

    public static FollowListVO empty() {
        return new FollowListVO(Collections.emptyList(), null, false);
    }
}
