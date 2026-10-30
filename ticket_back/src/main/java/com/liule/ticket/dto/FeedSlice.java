package com.liule.ticket.dto;

import com.liule.ticket.entity.Post;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 一页 Feed 结果。
 * <p>
 * fallbackGlobal 只对关注流有意义：用户没有任何关注时回落到全站最新，
 * 前端据此提示「关注后这里会出现动态」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeedSlice {

    private List<Post> posts;
    private FeedCursor nextCursor;
    private boolean hasMore;
    private boolean fallbackGlobal;

    public static FeedSlice empty() {
        return new FeedSlice(Collections.emptyList(), null, false, false);
    }
}
