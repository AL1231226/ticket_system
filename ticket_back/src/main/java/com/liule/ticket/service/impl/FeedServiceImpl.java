package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FeedSlice;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.service.FeedPolicy;
import com.liule.ticket.service.IFeedService;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FeedServiceImpl implements IFeedService {

    @Resource
    private PostMapper postMapper;

    @Resource
    private FollowMapper followMapper;

    @Resource
    private UserInfoMapper userInfoMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FeedPolicy feedPolicy;

    @Override
    public void onPostCreated(Post post) {
        if (post == null || post.getId() == null || post.getUserId() == null) {
            return;
        }
        try {
            long score = scoreOf(post.getCreateTime());
            String member = String.valueOf(post.getId());

            // 全站和作者自己的 Feed 所有作者都写；大 V 的内容靠作者 Feed 被读时合并
            zaddAndTrim(RedisConstants.FEED_GLOBAL_KEY, member, score, SystemConstants.FEED_GLOBAL_MAX);
            zaddAndTrim(RedisConstants.FEED_USER_KEY + post.getUserId(), member, score,
                    SystemConstants.FEED_USER_MAX);
            //粉丝超过1000，不采用推拉方式
            if (!feedPolicy.shouldFanout(fansOf(post.getUserId()))) {
                log.debug("大 V 不扇出，postId={}, authorId={}", post.getId(), post.getUserId());
                return;
            }
            // 推出给作者的所有粉丝
            List<Long> followers = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                            .eq(Follow::getFollowUserId, post.getUserId()))
                    .stream()
                    .map(Follow::getUserId)
                    .collect(Collectors.toList());
            for (Long followerId : followers) {
                zaddAndTrim(RedisConstants.FEED_USER_KEY + followerId, member, score,
                        SystemConstants.FEED_USER_MAX);
            }
        } catch (Exception e) {
            // 发布已落库，Redis 失败不能反向影响业务；读路径会从 DB 重建
            log.warn("Feed 扇出失败，postId={}", post.getId(), e);
        }
    }
    // 获取粉丝数
    private long fansOf(Long userId) {
        UserInfo info = userInfoMapper.selectById(userId);
        return info == null || info.getFans() == null ? 0 : info.getFans();
    }

    // 计算分数
    private long scoreOf(LocalDateTime createTime) {
        return createTime == null
                ? System.currentTimeMillis()
                : createTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
    // 向 ZSet 添加一个元素，并维护元素数量不超过最大值
    private void zaddAndTrim(String key, String member, long score, int max) {
        stringRedisTemplate.opsForZSet().add(key, member, score);
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size != null && size > max) {
            stringRedisTemplate.opsForZSet().removeRange(key, 0, size - max - 1);
        }
    }
    //全站最新的动态
    @Override
    public FeedSlice readGlobal(FeedCursor cursor, int count) {
        int size = normalizeCount(count);
        try {
            // 确保全站 Feed 不为空
            ensureGlobalFeed();
            return readRedisFeed(RedisConstants.FEED_GLOBAL_KEY, List.of(), cursor, size, false);
        } catch (Exception e) {
            log.warn("全站 Feed 读取失败，回退 DB", e);
            return readDbFeed(null, cursor, size);
        }
    }

    @Override
    public FeedSlice readFollow(Long userId, FeedCursor cursor, int count) {
        if (userId == null) {
            return readGlobal(cursor, count);
        }
        int size = normalizeCount(count);
        try {
            //作者的关注
            List<Long> followees = followeesOf(userId);
            if (followees.isEmpty()) {
                FeedSlice slice = readGlobal(cursor, size);
                slice.setFallbackGlobal(true);
                return slice;
            }

            String key = RedisConstants.FEED_USER_KEY + userId;
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                rebuildUserFeed(userId);
            }

            List<Long> bigVs = followees.stream()
                    .filter(followeeId -> !feedPolicy.shouldFanout(fansOf(followeeId)))
                    .collect(Collectors.toList());
            return readRedisFeed(key, bigVs, cursor, size, false);
        } catch (Exception e) {
            log.warn("关注 Feed 读取失败，回退 DB，userId={}", userId, e);
            List<Long> authors = new ArrayList<>(followeesOf(userId));
            authors.add(userId);
            return readDbFeed(authors, cursor, size);
        }
    }

    private int normalizeCount(int count) {
        if (count <= 0) {
            return 10;
        }
        return Math.min(count, 50);
    }

    private void ensureGlobalFeed() {
        Long size = stringRedisTemplate.opsForZSet().zCard(RedisConstants.FEED_GLOBAL_KEY);
        if (size != null && size > 0) {
            return;
        }
        List<Post> recent = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getStatus, 0)
                .orderByDesc(Post::getCreateTime)
                .orderByDesc(Post::getId)
                .last("LIMIT " + SystemConstants.FEED_GLOBAL_MAX));
        for (Post post : recent) {
            stringRedisTemplate.opsForZSet().add(RedisConstants.FEED_GLOBAL_KEY,
                    String.valueOf(post.getId()), scoreOf(post.getCreateTime()));
        }
    }

    /**
     * 从主 Feed（个人 ZSet 或全站 ZSet）取一页，并合并大 V 的作者 Feed。
     * 每个来源都按同一个时间边界截取，再归并、去重、排序，保证分页不重不漏。
     */
    private FeedSlice readRedisFeed(String primaryKey, List<Long> bigVIds, FeedCursor cursor,
                                    int count, boolean fallbackGlobal) {
        //确定边界
        Boundary boundary = resolveBoundary(primaryKey, cursor);

        Map<Long, ScoredId> merged = new LinkedHashMap<>();
        // 多取 2 条：一条用于判断 hasMore，另一条兜住多个来源重叠导致去重后少一条的情况
        mergeInto(merged, fetch(primaryKey, boundary, count + 2));
        for (Long bigVId : bigVIds) {
            mergeInto(merged, fetch(RedisConstants.FEED_USER_KEY + bigVId, boundary, count + 2));
        }
        // 合并并排序
        List<ScoredId> ordered = new ArrayList<>(merged.values());
        ordered.sort((a, b) -> {
            int byScore = Long.compare(b.score, a.score);
            return byScore != 0 ? byScore : Long.compare(b.id, a.id);
        });

        // offset 是「合并后、边界毫秒里已经返回过多少条」，只能统一跳，不能交给各来源各跳一次
        List<ScoredId> afterBoundary = skipBoundary(ordered, boundary);
        boolean hasMore = afterBoundary.size() > count;
        List<ScoredId> page = afterBoundary.subList(0, Math.min(count, afterBoundary.size()));
        List<Post> posts = loadPostsInOrder(page);
        return new FeedSlice(posts, nextCursor(page, boundary), hasMore, fallbackGlobal);
    }

    private Boundary resolveBoundary(String key, FeedCursor cursor) {
        //从首页开始
        if (cursor == null || cursor.isEmpty()) {
            return Boundary.first();
        }
        // 根据 lastId 定位边界
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(cursor.getLastId()));
        if (score != null) {
            return Boundary.score(score.longValue(), cursor.getOffset() == null ? 0 : cursor.getOffset());
        }
        // 被裁剪 / 下架后从 ZSet 消失，但 DB 还在：用 DB 的发布时间恢复边界
        Post last = postMapper.selectById(cursor.getLastId());
        if (last != null && last.getCreateTime() != null) {
            return Boundary.score(scoreOf(last.getCreateTime()),
                    cursor.getOffset() == null ? 0 : cursor.getOffset());
        }
        // 真正被硬删，无法定位；按第一页处理
        return Boundary.first();
    }

    private List<ScoredId> fetch(String key, Boundary boundary, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples;
        // 第一页,没有边界
        if (boundary.first) {
            tuples = stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, limit - 1L);
        } else {
            // 多取 boundary.offset 条，把边界毫秒里已返回的重复项取出来，由合并后的统一跳过处理
            tuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(
                    key, 0, boundary.score, 0, boundary.offset + limit);
        }
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        return tuples.stream()
                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                .map(tuple -> new ScoredId(Long.valueOf(tuple.getValue()), tuple.getScore().longValue()))
                .collect(Collectors.toList());
    }

    private List<ScoredId> skipBoundary(List<ScoredId> ordered, Boundary boundary) {
        if (boundary.first || boundary.offset <= 0) {
            return ordered;
        }
        List<ScoredId> rest = new ArrayList<>(ordered.size());
        int skipped = 0;
        for (ScoredId item : ordered) {
            if (item.score == boundary.score && skipped < boundary.offset) {
                skipped++;
                continue;
            }
            rest.add(item);
        }
        return rest;
    }

    private void mergeInto(Map<Long, ScoredId> merged, List<ScoredId> incoming) {
        for (ScoredId item : incoming) {
            merged.putIfAbsent(item.id, item);
        }
    }

    private List<Post> loadPostsInOrder(List<ScoredId> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        List<Long> ids = page.stream().map(item -> item.id).collect(Collectors.toList());
        Map<Long, Post> byId = postMapper.selectList(new LambdaQueryWrapper<Post>()
                        .in(Post::getId, ids))
                .stream()
                .collect(Collectors.toMap(Post::getId, post -> post));
        List<Post> posts = new ArrayList<>(page.size());
        for (ScoredId item : page) {
            Post post = byId.get(item.id);
            if (post != null && Integer.valueOf(0).equals(post.getStatus())) {
                posts.add(post);
            }
        }
        return posts;
    }

    private FeedCursor nextCursor(List<ScoredId> page, Boundary boundary) {
        if (page.isEmpty()) {
            return null;
        }
        ScoredId last = page.get(page.size() - 1);
        int sameScore = 0;
        for (ScoredId item : page) {
            if (item.score == last.score) {
                sameScore++;
            }
        }
        // 上一页的边界就在这个毫秒时，offset 要累加；否则从本页同毫秒的第一条开始数
        int previous = !boundary.first && boundary.score == last.score ? boundary.offset : 0;
        return FeedCursor.of(last.id, previous + sameScore);
    }

    private FeedSlice readDbFeed(List<Long> authorIds, FeedCursor cursor, int count) {
        int skip = cursor == null || cursor.getOffset() == null ? 0 : Math.max(0, cursor.getOffset());
        List<Post> rows = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(authorIds != null && !authorIds.isEmpty(), Post::getUserId, authorIds)
                .eq(Post::getStatus, 0)
                .orderByDesc(Post::getCreateTime)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (skip + count + 1)));
        List<Post> afterSkip = rows.stream().skip(skip).collect(Collectors.toList());
        boolean hasMore = afterSkip.size() > count;
        List<Post> posts = afterSkip.stream().limit(count).collect(Collectors.toList());
        // DB 回退按绝对位置翻页，offset 记的是已经跳过的总条数
        FeedCursor next = posts.isEmpty()
                ? null
                : FeedCursor.of(posts.get(posts.size() - 1).getId(), skip + posts.size());
        return new FeedSlice(posts, next, hasMore, false);
    }

    private static class Boundary {
        private final boolean first;
        private final long score;
        private final int offset;

        private Boundary(boolean first, long score, int offset) {
            this.first = first;
            this.score = score;
            this.offset = offset;
        }

        static Boundary first() {
            return new Boundary(true, 0, 0);
        }

        static Boundary score(long score, int offset) {
            return new Boundary(false, score, offset);
        }
    }

    private static class ScoredId {
        private final long id;
        private final long score;

        private ScoredId(long id, long score) {
            this.id = id;
            this.score = score;
        }
    }

    @Override
    public void onFollowed(Long userId, Long followeeId) {
        if (userId == null || followeeId == null || userId.equals(followeeId)) {
            return;
        }
        try {
            // 大 V 原本就不进个人 Feed，关注时不回填，读时合并
            if (!feedPolicy.shouldFanout(fansOf(followeeId))) {
                return;
            }
            List<Post> recent = recentPostsOf(followeeId, SystemConstants.FEED_FOLLOW_BACKFILL_SIZE);
            for (Post post : recent) {
                zaddAndTrim(RedisConstants.FEED_USER_KEY + userId, String.valueOf(post.getId()),
                        scoreOf(post.getCreateTime()), SystemConstants.FEED_USER_MAX);
            }
        } catch (Exception e) {
            log.warn("关注回填失败，userId={}, followeeId={}", userId, followeeId, e);
        }
    }

    private List<Post> recentPostsOf(Long userId, int limit) {
        return postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getStatus, 0)
                .orderByDesc(Post::getCreateTime)
                .orderByDesc(Post::getId)
                .last("LIMIT " + limit));
    }

    @Override
    public void onUnfollowed(Long userId, Long followeeId) {
        if (userId == null || followeeId == null) {
            return;
        }
        try {
            String key = RedisConstants.FEED_USER_KEY + userId;
            Set<String> members = stringRedisTemplate.opsForZSet()
                    .reverseRange(key, 0, SystemConstants.FEED_UNFOLLOW_SCAN_SIZE - 1);
            if (members == null || members.isEmpty()) {
                return;
            }

            List<Long> postIds = members.stream().map(Long::valueOf).collect(Collectors.toList());
            List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .in(Post::getId, postIds));
            Object[] toRemove = posts.stream()
                    .filter(post -> followeeId.equals(post.getUserId()))
                    .map(post -> String.valueOf(post.getId()))
                    .toArray();
            if (toRemove.length > 0) {
                stringRedisTemplate.opsForZSet().remove(key, toRemove);
            }
        } catch (Exception e) {
            log.warn("取关清理失败，userId={}, followeeId={}", userId, followeeId, e);
        }
    }

    @Override
    public void onPostDeleted(Post post) {
        if (post == null || post.getId() == null) {
            return;
        }
        try {
            String member = String.valueOf(post.getId());
            stringRedisTemplate.opsForZSet().remove(RedisConstants.FEED_GLOBAL_KEY, member);
            if (post.getUserId() != null) {
                stringRedisTemplate.opsForZSet()
                        .remove(RedisConstants.FEED_USER_KEY + post.getUserId(), member);
            }
        } catch (Exception e) {
            log.warn("删除动态后清理 Feed 失败，postId={}", post.getId(), e);
        }
    }

    @Override
    public void rebuildUserFeed(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            List<Long> authors = followeesOf(userId).stream()
                    .filter(followeeId -> feedPolicy.shouldFanout(fansOf(followeeId)))
                    .collect(Collectors.toCollection(ArrayList::new));
            authors.add(userId);

            List<Post> recent = postMapper.selectList(new LambdaQueryWrapper<Post>()
                    .in(Post::getUserId, authors)
                    .eq(Post::getStatus, 0)
                    .orderByDesc(Post::getCreateTime)
                    .orderByDesc(Post::getId)
                    .last("LIMIT " + SystemConstants.FEED_REBUILD_SIZE));

            String key = RedisConstants.FEED_USER_KEY + userId;
            stringRedisTemplate.delete(key);
            for (Post post : recent) {
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(post.getId()),
                        scoreOf(post.getCreateTime()));
            }
        } catch (Exception e) {
            log.warn("个人 Feed 重建失败，userId={}", userId, e);
        }
    }

    private List<Long> followeesOf(Long userId) {
        return followMapper.selectList(new LambdaQueryWrapper<Follow>()
                        .eq(Follow::getUserId, userId))
                .stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
    }
}
