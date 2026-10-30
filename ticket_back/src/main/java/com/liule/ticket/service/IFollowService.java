package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.Follow;

/**
 * 关注关系（v1 不做关注通知 / 推荐）。
 */
public interface IFollowService extends IService<Follow> {

    /** 关注 / 取关；幂等，返回当前关系和目标粉丝数 */
    Result toggle(Long targetUserId, boolean follow);

    /** type=followees 关注列表 / type=fans 粉丝列表，公开，lastId 游标 */
    Result list(Long userId, String type, FeedCursor cursor, int count);

    /** 我和目标用户的共同关注，必须登录 */
    Result common(Long targetUserId);

    /** 当前用户是否关注目标用户；直接读 tb_follow */
    boolean isFollowing(Long userId, Long targetUserId);
}
