package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 一页动态列表：动态 VO + 下一页游标 + 是否还有更多。
 * <p>
 * fallbackGlobal 只对关注流有意义：用户没有关注任何人时回落到全站最新，
 * 前端据此提示「关注后这里会出现动态」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostPageVO {

    private List<PostVO> posts;
    private FeedCursor nextCursor;
    private boolean hasMore;
    private boolean fallbackGlobal;

    public static PostPageVO empty() {
        return new PostPageVO(Collections.emptyList(), null, false, false);
    }
}
