package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FeedSlice;
import com.liule.ticket.dto.PostCreateDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feed 推拉结合的行为验证。
 * <p>
 * 用 99001–99004 这组专用测试用户，不碰种子用户；每个用例前后清掉
 * 测试动态、关注关系、用户信息和对应的 Redis key，全站 Feed 只移除测试动态。
 */
@SpringBootTest
class FeedServiceImplTest {

    private static final long AUTHOR = 99001L;
    private static final long FOLLOWER = 99002L;
    private static final long BIG_V = 99003L;
    private static final long OTHER = 99004L;
    private static final List<Long> TEST_USERS = Arrays.asList(AUTHOR, FOLLOWER, BIG_V, OTHER);

    @Autowired
    private IFeedService feedService;

    @Autowired
    private IPostService postService;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        clean();
        insertUserInfo(AUTHOR, 2);
        insertUserInfo(FOLLOWER, 0);
        insertUserInfo(BIG_V, 1001);
        insertUserInfo(OTHER, 0);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void publishFanoutWritesGlobalAuthorAndFollowerFeeds() {
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        Post post = insertPost(AUTHOR, "普通作者发布", LocalDateTime.now());

        feedService.onPostCreated(post);

        assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).contains(post.getId().toString());
        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + AUTHOR)).contains(post.getId().toString());
        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER)).contains(post.getId().toString());
    }

    @Test
    void bigVPostIsNotPushedToFollowers() {
        followMapper.insertIgnore(FOLLOWER, BIG_V);
        Post post = insertPost(BIG_V, "大 V 发布", LocalDateTime.now());

        feedService.onPostCreated(post);

        assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).contains(post.getId().toString());
        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + BIG_V)).contains(post.getId().toString());
        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER))
                .doesNotContain(post.getId().toString());
    }

    @Test
    void followBackfillAddsAtMostTwentyRecentPosts() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 12, 0);
        List<Post> posts = new java.util.ArrayList<>();
        for (int i = 0; i < 22; i++) {
            posts.add(insertPost(AUTHOR, "关注前动态 " + i, base.plusSeconds(i)));
        }
        followMapper.insertIgnore(FOLLOWER, AUTHOR);

        feedService.onFollowed(FOLLOWER, AUTHOR);

        Set<String> feed = feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER);
        assertThat(feed).hasSize(20);
        assertThat(feed).contains(
                posts.get(2).getId().toString(),
                posts.get(21).getId().toString());
        assertThat(feed).doesNotContain(
                posts.get(0).getId().toString(),
                posts.get(1).getId().toString());
    }

    @Test
    void followBigVDoesNotBackfill() {
        Post post = insertPost(BIG_V, "大 V 的旧动态", LocalDateTime.now());
        followMapper.insertIgnore(FOLLOWER, BIG_V);

        feedService.onFollowed(FOLLOWER, BIG_V);

        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER))
                .doesNotContain(post.getId().toString());
    }

    @Test
    void unfollowRemovesOnlyThatAuthorsPostsFromPersonalFeed() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 2, 12, 0);
        Post kept = insertPost(OTHER, "另一个作者", base);
        Post removedA = insertPost(AUTHOR, "要清掉的 A", base.plusSeconds(1));
        Post removedB = insertPost(AUTHOR, "要清掉的 B", base.plusSeconds(2));
        followMapper.insertIgnore(FOLLOWER, OTHER);
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        feedService.onFollowed(FOLLOWER, OTHER);
        feedService.onFollowed(FOLLOWER, AUTHOR);

        feedService.onUnfollowed(FOLLOWER, AUTHOR);

        Set<String> feed = feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER);
        assertThat(feed).contains(kept.getId().toString());
        assertThat(feed).doesNotContain(removedA.getId().toString(), removedB.getId().toString());
    }

    @Test
    void rebuildIncludesOnlyNormalFolloweesAndSelf() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 3, 12, 0);
        Post normalPost = insertPost(AUTHOR, "普通关注对象的动态", base);
        Post bigVPost = insertPost(BIG_V, "大 V 的动态", base.plusSeconds(1));
        Post ownPost = insertPost(FOLLOWER, "自己的动态", base.plusSeconds(2));
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        followMapper.insertIgnore(FOLLOWER, BIG_V);
        stringRedisTemplate.delete(RedisConstants.FEED_USER_KEY + FOLLOWER);

        feedService.rebuildUserFeed(FOLLOWER);

        Set<String> feed = feedMembers(RedisConstants.FEED_USER_KEY + FOLLOWER);
        assertThat(feed).contains(normalPost.getId().toString(), ownPost.getId().toString());
        assertThat(feed).doesNotContain(bigVPost.getId().toString());
    }

    @Test
    void readGlobalReturnsNewestFirstAndPaginatesByCursor() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 30, 12, 0);
        Post first = insertPost(AUTHOR, "全站 1", base);
        Post second = insertPost(AUTHOR, "全站 2", base.plusSeconds(1));
        Post third = insertPost(AUTHOR, "全站 3", base.plusSeconds(2));
        feedService.onPostCreated(first);
        feedService.onPostCreated(second);
        feedService.onPostCreated(third);

        FeedSlice page1 = feedService.readGlobal(FeedCursor.empty(), 2);
        FeedSlice page2 = feedService.readGlobal(page1.getNextCursor(), 2);

        assertThat(page1.getPosts()).extracting(Post::getId)
                .containsExactly(third.getId(), second.getId());
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page2.getPosts()).extracting(Post::getId)
                .doesNotContain(third.getId(), second.getId())
                .contains(first.getId());
    }

    @Test
    void readGlobalDoesNotRepeatPostsWithSameMillisecond() {
        LocalDateTime sameMoment = LocalDateTime.of(2026, 10, 31, 12, 0);
        List<Post> posts = List.of(
                insertPost(AUTHOR, "同毫秒 1", sameMoment),
                insertPost(AUTHOR, "同毫秒 2", sameMoment),
                insertPost(AUTHOR, "同毫秒 3", sameMoment));
        posts.forEach(feedService::onPostCreated);

        FeedSlice page1 = feedService.readGlobal(FeedCursor.empty(), 2);
        FeedSlice page2 = feedService.readGlobal(page1.getNextCursor(), 2);

        // 第二页可能补上更早的动态，关键是不能重复、也不能漏掉另一条同毫秒动态
        List<Long> pageIds = new java.util.ArrayList<>();
        page1.getPosts().forEach(post -> pageIds.add(post.getId()));
        page2.getPosts().forEach(post -> pageIds.add(post.getId()));

        assertThat(page1.getPosts()).hasSize(2);
        assertThat(pageIds).doesNotHaveDuplicates();
        assertThat(pageIds).containsAll(
                posts.stream().map(Post::getId).collect(Collectors.toList()));
    }

    @Test
    void readFollowMergesBigVPostsAndDedupes() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 1, 12, 0);
        Post normalPost = insertPost(AUTHOR, "普通关注对象", base);
        Post bigVPost1 = insertPost(BIG_V, "大 V 第一条", base.plusSeconds(1));
        Post bigVPost2 = insertPost(BIG_V, "大 V 第二条", base.plusSeconds(2));
        Post ownPost = insertPost(FOLLOWER, "自己的动态", base.plusSeconds(3));
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        followMapper.insertIgnore(FOLLOWER, BIG_V);
        feedService.onPostCreated(normalPost);
        feedService.onPostCreated(bigVPost1);
        feedService.onPostCreated(bigVPost2);
        feedService.onPostCreated(ownPost);

        // 强制重建：个人 Feed 只放普通关注 + 自己，大 V 内容必须靠读时合并
        stringRedisTemplate.delete(RedisConstants.FEED_USER_KEY + FOLLOWER);
        feedService.rebuildUserFeed(FOLLOWER);
        // 再手动塞一条大 V 动态，模拟它曾经被推送过，验证两路去重
        stringRedisTemplate.opsForZSet().add(
                RedisConstants.FEED_USER_KEY + FOLLOWER,
                String.valueOf(bigVPost1.getId()),
                bigVPost1.getCreateTime().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli());

        FeedSlice slice = feedService.readFollow(FOLLOWER, FeedCursor.empty(), 10);

        assertThat(slice.getPosts()).extracting(Post::getId).containsExactly(
                ownPost.getId(), bigVPost2.getId(), bigVPost1.getId(), normalPost.getId());
    }

    @Test
    void publishWritesFeedAfterTransactionCommits() {
        UserDTO user = new UserDTO();
        user.setId(AUTHOR);
        UserHolder.saveUser(user);
        try {
            PostCreateDTO dto = new PostCreateDTO();
            dto.setContent("通过 PostService 发布");
            Result result = postService.publish(dto);

            assertThat(result.getSuccess()).isTrue();
            Long postId = (Long) result.getData();
            assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).contains(postId.toString());
            assertThat(feedMembers(RedisConstants.FEED_USER_KEY + AUTHOR)).contains(postId.toString());
            assertThat(postMapper.selectById(postId).getCreateTime()).isNotNull();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void readFollowPaginatesAcrossSourcesWithSameMillisecondBoundary() {
        LocalDateTime base = LocalDateTime.of(2026, 11, 3, 12, 0);
        Post normalAtBoundary = insertPost(AUTHOR, "普通-边界毫秒", base.plusSeconds(2));
        Post bigVAtBoundary = insertPost(BIG_V, "大 V-边界毫秒", base.plusSeconds(2));
        Post bigVOlder = insertPost(BIG_V, "大 V-更早", base.plusSeconds(1));
        Post own = insertPost(FOLLOWER, "自己-最新", base.plusSeconds(3));
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        followMapper.insertIgnore(FOLLOWER, BIG_V);
        feedService.onPostCreated(normalAtBoundary);
        feedService.onPostCreated(bigVAtBoundary);
        feedService.onPostCreated(bigVOlder);
        feedService.onPostCreated(own);
        stringRedisTemplate.delete(RedisConstants.FEED_USER_KEY + FOLLOWER);
        feedService.rebuildUserFeed(FOLLOWER);

        FeedSlice page1 = feedService.readFollow(FOLLOWER, FeedCursor.empty(), 2);
        FeedSlice page2 = feedService.readFollow(FOLLOWER, page1.getNextCursor(), 2);

        assertThat(page1.getPosts()).extracting(Post::getId)
                .containsExactly(own.getId(), bigVAtBoundary.getId());
        assertThat(page2.getPosts()).extracting(Post::getId)
                .containsExactly(normalAtBoundary.getId(), bigVOlder.getId());
    }

    @Test
    void readGlobalRebuildsEmptyZSetFromDb() {
        Post post = insertPost(AUTHOR, "DB 重建全站流", LocalDateTime.of(2026, 11, 4, 12, 0));
        stringRedisTemplate.delete(RedisConstants.FEED_GLOBAL_KEY);

        FeedSlice slice = feedService.readGlobal(FeedCursor.empty(), 50);

        assertThat(slice.getPosts()).extracting(Post::getId).contains(post.getId());
        assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).isNotEmpty();
    }

    @Test
    void readFollowRebuildsMissingPersonalFeedFromDb() {
        Post post = insertPost(AUTHOR, "重建关注流", LocalDateTime.of(2026, 11, 5, 12, 0));
        followMapper.insertIgnore(FOLLOWER, AUTHOR);
        stringRedisTemplate.delete(RedisConstants.FEED_USER_KEY + FOLLOWER);

        FeedSlice slice = feedService.readFollow(FOLLOWER, FeedCursor.empty(), 50);

        assertThat(slice.getPosts()).extracting(Post::getId).contains(post.getId());
    }

    @Test
    void readFollowWithoutFolloweesFallsBackToGlobal() {
        Post post = insertPost(AUTHOR, "全站可见", LocalDateTime.of(2026, 11, 2, 12, 0));
        feedService.onPostCreated(post);

        FeedSlice slice = feedService.readFollow(FOLLOWER, FeedCursor.empty(), 10);

        assertThat(slice.isFallbackGlobal()).isTrue();
        assertThat(slice.getPosts()).extracting(Post::getId).contains(post.getId());
    }

    private void insertUserInfo(long userId, int fans) {
        userInfoMapper.insert(new UserInfo()
                .setUserId(userId)
                .setFans(fans)
                .setFollowee(0)
                .setCredits(0)
                .setLevel(1)
                .setShowCount(0));
    }

    private Post insertPost(long userId, String content, LocalDateTime createTime) {
        Post post = new Post()
                .setUserId(userId)
                .setTitle("")
                .setContent(content)
                .setImages("")
                .setLiked(0)
                .setComments(0)
                .setStatus(0)
                .setCreateTime(createTime);
        postMapper.insert(post);
        return post;
    }

    private Set<String> feedMembers(String key) {
        Set<String> members = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        return members == null ? Set.of() : members;
    }

    private void clean() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getUserId, TEST_USERS));
        if (!posts.isEmpty()) {
            List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
            Object[] members = postIds.stream().map(String::valueOf).toArray();
            stringRedisTemplate.opsForZSet().remove(RedisConstants.FEED_GLOBAL_KEY, members);
            postMapper.deleteByIds(postIds);
        }

        stringRedisTemplate.delete(TEST_USERS.stream()
                .map(id -> RedisConstants.FEED_USER_KEY + id)
                .collect(Collectors.toList()));

        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .in(Follow::getUserId, TEST_USERS)
                .or()
                .in(Follow::getFollowUserId, TEST_USERS));

        userInfoMapper.deleteByIds(TEST_USERS);
    }
}
