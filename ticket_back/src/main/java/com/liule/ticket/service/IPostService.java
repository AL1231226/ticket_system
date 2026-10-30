package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.PostCreateDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.Post;

/**
 * 社区动态。
 * <p>
 * 空壳阶段只声明接口，具体实现在 E12-5 之后补齐。
 */
public interface IPostService extends IService<Post> {




    Result publish(PostCreateDTO dto);

    /** 动态详情；游客可读，登录时带 likedByMe */
    Result detail(Long postId);

    /** 软删除，仅作者本人 */
    Result delete(Long postId);

    /** 社区流：mode=follow 走关注流，其余走全站 */
    Result scroll(String mode, FeedCursor cursor, int count);

    /** 某用户的动态 */
    Result ofUser(Long userId, FeedCursor cursor, int count);

    /** 某演出的动态 */
    Result ofShow(Long showId, FeedCursor cursor, int count);

    /** 点赞切换：未点赞则点赞，已点赞则取消；返回本次状态和总数 */
    Result toggleLike(Long postId);

    /** 最近点赞用户，limit 默认 5，最多 20 */
    Result likedUsers(Long postId, int limit);
}
