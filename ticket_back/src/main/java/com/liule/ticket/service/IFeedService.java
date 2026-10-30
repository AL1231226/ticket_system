package com.liule.ticket.service;

import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FeedSlice;
import com.liule.ticket.entity.Post;

/**
 * Feed 推拉结合。
 * <p>
 * 普通作者发布时扇出到粉丝的个人 ZSet；大 V 不推送，读关注流时再合并。
 * 关注 / 取关 / 重建都由这里维护 Redis 镜像，Redis 失败以 DB 为准。
 */
public interface IFeedService {

    //动态发布后推到全站，个人动态以及作者的粉丝
    void onPostCreated(Post post);

    /** 全站最新 */
    FeedSlice readGlobal(FeedCursor cursor, int count);

    /** 个人关注流；无关注时回落全站并标记 fallbackGlobal */
    FeedSlice readFollow(Long userId, FeedCursor cursor, int count);

    /** 关注成功后回填对方最近动态；大 V 跳过 */
    void onFollowed(Long userId, Long followeeId);

    /** 取关后从个人 Feed 移除对方动态 */
    void onUnfollowed(Long userId, Long followeeId);

    /** 动态软删除后从全站和作者 Feed 移除；粉丝 Feed 由读取时按 status 过滤兜底 */
    void onPostDeleted(Post post);

    /** Redis key 丢失时，从 DB 重建个人 Feed */
    void rebuildUserFeed(Long userId);
}
