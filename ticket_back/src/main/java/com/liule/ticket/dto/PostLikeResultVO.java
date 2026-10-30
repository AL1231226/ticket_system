package com.liule.ticket.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞切换结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostLikeResultVO {

    /** true=本次点赞，false=本次取消 */
    private Boolean liked;

    /** 当前总点赞数，以 tb_post.liked 为准 */
    private Integer likedCount;
}
