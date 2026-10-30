package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FollowListVO;
import com.liule.ticket.dto.FollowResultVO;
import com.liule.ticket.dto.FollowUserVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.mapper.UserMapper;
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
 * E12-8 关注 / 粉丝 / 共同关注的行为验证。
 * <p>
 * 沿用 PostServiceImplTest 的方式：真实 MySQL + Redis，用 99401–99405 这组
 * 专用测试用户，不碰种子用户；每个用例前后清掉测试关注、动态、Redis 镜像。
 */
@SpringBootTest
class FollowServiceImplTest {

    private static final long ME = 99401L;
    private static final long A = 99402L;
    private static final long B = 99403L;
    private static final long C = 99404L;
    private static final long D = 99405L;
    private static final List<Long> TEST_USERS = Arrays.asList(ME, A, B, C, D);

    /** 用整秒时间，避免 MySQL TIMESTAMP 的小数截断影响游标定位 */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Autowired
    private IFollowService followService;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(ME, "测试我");
        insertUser(A, "用户A");
        insertUser(B, "用户B");
        insertUser(C, "用户C");
        insertUser(D, "用户D");
        insertUserInfo(ME, "intro-me");
        insertUserInfo(A, "intro-a");
        insertUserInfo(B, "intro-b");
        insertUserInfo(C, "intro-c");
        insertUserInfo(D, "intro-d");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ------------------------------------------------------------------
    // 关注 / 取关
    // ------------------------------------------------------------------

    @Test
    void followIncrementsBothCountsAndIsIdempotent() {
        UserHolder.saveUser(userOf(ME));
        try {
            Result first = followService.toggle(A, true);

            assertThat(first.getSuccess()).isTrue();
            FollowResultVO r1 = (FollowResultVO) first.getData();
            assertThat(r1.getFollowing()).isTrue();
            assertThat(r1.getFans()).isEqualTo(1);
            assertThat(followRows(ME, A)).isEqualTo(1L);
            assertThat(info(ME).getFollowee()).isEqualTo(1);
            assertThat(info(A).getFans()).isEqualTo(1);
            assertThat(setMembers(RedisConstants.FOLLOW_USER_KEY + ME))
                    .contains(String.valueOf(A));

            Result second = followService.toggle(A, true);

            assertThat(second.getSuccess()).isTrue();
            FollowResultVO r2 = (FollowResultVO) second.getData();
            assertThat(r2.getFollowing()).isTrue();
            assertThat(r2.getFans()).isEqualTo(1);
            assertThat(followRows(ME, A)).isEqualTo(1L);
            assertThat(info(ME).getFollowee()).isEqualTo(1);
            assertThat(info(A).getFans()).isEqualTo(1);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void unfollowDecrementsBothCountsAndIsIdempotent() {
        UserHolder.saveUser(userOf(ME));
        try {
            followService.toggle(A, true);

            Result first = followService.toggle(A, false);

            assertThat(first.getSuccess()).isTrue();
            FollowResultVO r1 = (FollowResultVO) first.getData();
            assertThat(r1.getFollowing()).isFalse();
            assertThat(r1.getFans()).isZero();
            assertThat(followRows(ME, A)).isZero();
            assertThat(info(ME).getFollowee()).isZero();
            assertThat(info(A).getFans()).isZero();
            assertThat(setMembers(RedisConstants.FOLLOW_USER_KEY + ME))
                    .doesNotContain(String.valueOf(A));

            Result second = followService.toggle(A, false);

            assertThat(second.getSuccess()).isTrue();
            assertThat(((FollowResultVO) second.getData()).getFollowing()).isFalse();
            assertThat(info(ME).getFollowee()).isZero();
            assertThat(info(A).getFans()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void followRejectsSelf() {
        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.toggle(ME, true);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("不能关注自己");
            assertThat(followRows(ME, ME)).isZero();
            assertThat(info(ME).getFollowee()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void followRejectsMissingTarget() {
        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.toggle(999999999L, true);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("用户不存在");
            assertThat(info(ME).getFollowee()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void toggleRequiresLogin() {
        Result result = followService.toggle(A, true);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        assertThat(followRows(ME, A)).isZero();
    }

    @Test
    void followCreatesUserInfoWhenMissing() {
        userInfoMapper.deleteById(ME);
        userInfoMapper.deleteById(A);

        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.toggle(A, true);

            assertThat(result.getSuccess()).isTrue();
            assertThat(info(ME)).isNotNull();
            assertThat(info(ME).getFollowee()).isEqualTo(1);
            assertThat(info(A)).isNotNull();
            assertThat(info(A).getFans()).isEqualTo(1);
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // Feed 回填 / 清理
    // ------------------------------------------------------------------

    @Test
    void followBackfillsRecentPostsIntoMyFeed() {
        Post older = insertPost(A, "A 的旧动态", BASE);
        Post newer = insertPost(A, "A 的新动态", BASE.plusSeconds(1));

        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.toggle(A, true);

            assertThat(result.getSuccess()).isTrue();
            assertThat(feedMembers(ME)).contains(older.getId().toString(), newer.getId().toString());
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void unfollowRemovesAuthorPostsFromMyFeed() {
        Post post = insertPost(A, "A 的动态", BASE);

        UserHolder.saveUser(userOf(ME));
        try {
            followService.toggle(A, true);
            assertThat(feedMembers(ME)).contains(post.getId().toString());

            Result result = followService.toggle(A, false);

            assertThat(result.getSuccess()).isTrue();
            assertThat(feedMembers(ME)).doesNotContain(post.getId().toString());
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // 关注 / 粉丝列表
    // ------------------------------------------------------------------

    @Test
    void listFolloweesReturnsNewestFirstWithCursor() {
        insertFollow(ME, A, BASE);
        insertFollow(ME, B, BASE.plusSeconds(1));
        insertFollow(ME, C, BASE.plusSeconds(2));
        insertFollow(D, A, BASE.plusSeconds(3));

        FollowListVO page1 = (FollowListVO) followService
                .list(ME, "followees", FeedCursor.empty(), 2).getData();
        FollowListVO page2 = (FollowListVO) followService
                .list(ME, "followees", page1.getNextCursor(), 2).getData();

        assertThat(page1.getUsers()).extracting(FollowUserVO::getUserId)
                .containsExactly(C, B);
        assertThat(page1.getUsers().get(0).getNickname()).isEqualTo("用户C");
        assertThat(page1.getUsers().get(0).getIntroduce()).isEqualTo("intro-c");
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page2.getUsers()).extracting(FollowUserVO::getUserId)
                .containsExactly(A);
        assertThat(page2.isHasMore()).isFalse();
    }

    @Test
    void listFansReturnsNewestFirstWithCursor() {
        insertFollow(ME, A, BASE);
        insertFollow(B, A, BASE.plusSeconds(1));
        insertFollow(C, A, BASE.plusSeconds(2));

        FollowListVO page1 = (FollowListVO) followService
                .list(A, "fans", FeedCursor.empty(), 2).getData();
        FollowListVO page2 = (FollowListVO) followService
                .list(A, "fans", page1.getNextCursor(), 2).getData();

        assertThat(page1.getUsers()).extracting(FollowUserVO::getUserId)
                .containsExactly(C, B);
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page2.getUsers()).extracting(FollowUserVO::getUserId)
                .containsExactly(ME);
        assertThat(page2.isHasMore()).isFalse();
    }

    @Test
    void listRejectsUnknownType() {
        Result result = followService.list(ME, "friends", FeedCursor.empty(), 10);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("type 只能是 followees 或 fans");
    }

    @Test
    void listRejectsMissingUser() {
        Result result = followService.list(999999999L, "followees", FeedCursor.empty(), 10);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("用户不存在");
    }

    // ------------------------------------------------------------------
    // 共同关注
    // ------------------------------------------------------------------

    @Test
    void commonReturnsIntersectionAndBackfillsRedis() {
        insertFollow(ME, A, BASE);
        insertFollow(ME, B, BASE.plusSeconds(1));
        insertFollow(ME, C, BASE.plusSeconds(2));
        insertFollow(D, A, BASE);
        insertFollow(D, B, BASE.plusSeconds(1));
        stringRedisTemplate.delete(List.of(
                RedisConstants.FOLLOW_USER_KEY + ME,
                RedisConstants.FOLLOW_USER_KEY + D));

        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.common(D);

            assertThat(result.getSuccess()).isTrue();
            @SuppressWarnings("unchecked")
            List<FollowUserVO> users = (List<FollowUserVO>) result.getData();
            assertThat(users).extracting(FollowUserVO::getUserId)
                    .containsExactly(B, A);
            assertThat(users.get(0).getNickname()).isEqualTo("用户B");
            assertThat(setMembers(RedisConstants.FOLLOW_USER_KEY + ME))
                    .contains(String.valueOf(A), String.valueOf(B), String.valueOf(C));
            assertThat(setMembers(RedisConstants.FOLLOW_USER_KEY + D))
                    .contains(String.valueOf(A), String.valueOf(B));
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void commonSqlFallbackReturnsIntersection() {
        insertFollow(ME, A, BASE);
        insertFollow(ME, B, BASE.plusSeconds(1));
        insertFollow(ME, C, BASE.plusSeconds(2));
        insertFollow(D, A, BASE);
        insertFollow(D, B, BASE.plusSeconds(1));

        List<Long> common = followMapper.selectCommon(ME, D);

        assertThat(common).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void commonRequiresLogin() {
        Result result = followService.common(D);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
    }

    @Test
    void commonRejectsMissingUser() {
        UserHolder.saveUser(userOf(ME));
        try {
            Result result = followService.common(999999999L);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("用户不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private UserDTO userOf(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        return user;
    }

    private void insertUser(long id, String nickname) {
        userMapper.insert(new User()
                .setId(id)
                .setPhone("199000" + id)
                .setPassword("")
                .setNickname(nickname)
                .setIcon("https://img/avatar.png"));
    }

    private void insertUserInfo(long userId, String introduce) {
        userInfoMapper.insert(new UserInfo()
                .setUserId(userId)
                .setIntroduce(introduce)
                .setFans(0)
                .setFollowee(0)
                .setCredits(0)
                .setLevel(1)
                .setShowCount(0));
    }

    private Follow insertFollow(long userId, long followUserId, LocalDateTime createTime) {
        Follow follow = new Follow()
                .setUserId(userId)
                .setFollowUserId(followUserId)
                .setCreateTime(createTime);
        followMapper.insert(follow);
        return follow;
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

    private UserInfo info(long userId) {
        return userInfoMapper.selectById(userId);
    }

    private long followRows(long userId, long followUserId) {
        return followMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
    }

    private Set<String> setMembers(String key) {
        Set<String> members = stringRedisTemplate.opsForSet().members(key);
        return members == null ? Set.of() : members;
    }

    private Set<String> feedMembers(long userId) {
        Set<String> members = stringRedisTemplate.opsForZSet()
                .range(RedisConstants.FEED_USER_KEY + userId, 0, -1);
        return members == null ? Set.of() : members;
    }

    private void clean() {
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .in(Follow::getUserId, TEST_USERS)
                .or()
                .in(Follow::getFollowUserId, TEST_USERS));
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getUserId, TEST_USERS));
        List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
        if (!postIds.isEmpty()) {
            postMapper.deleteByIds(postIds);
        }
        stringRedisTemplate.delete(TEST_USERS.stream()
                .flatMap(id -> java.util.stream.Stream.of(
                        RedisConstants.FEED_USER_KEY + id,
                        RedisConstants.FOLLOW_USER_KEY + id))
                .collect(Collectors.toList()));
        userInfoMapper.deleteByIds(TEST_USERS);
        userMapper.deleteByIds(TEST_USERS);
    }
}
