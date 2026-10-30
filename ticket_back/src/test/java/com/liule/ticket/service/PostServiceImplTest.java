package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.PostCreateDTO;
import com.liule.ticket.dto.PostLikeResultVO;
import com.liule.ticket.dto.PostLikeUserVO;
import com.liule.ticket.dto.PostPageVO;
import com.liule.ticket.dto.PostVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.PostLike;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.PostLikeMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.ShowMapper;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E12-5 / E12-6 动态 CRUD 与点赞的行为验证。
 * <p>
 * 沿用 FeedServiceImplTest 的方式：真实 MySQL + Redis，用 99101–99103 和
 * 99201–99206 这组专用测试用户，不碰种子用户；每个用例前后清掉测试动态、
 * 点赞、关注和 Redis 镜像。
 */
@SpringBootTest
class PostServiceImplTest {

    private static final long AUTHOR = 99101L;
    private static final long VIEWER = 99102L;
    private static final long OTHER = 99103L;
    private static final List<Long> TEST_USERS = Arrays.asList(
            AUTHOR, VIEWER, OTHER,
            99201L, 99202L, 99203L, 99204L, 99205L, 99206L);

    /** 用整秒时间，避免 MySQL TIMESTAMP 的小数截断影响游标定位 */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 29, 12, 0);

    /** 演出 3 在种子里没有任何动态，按演出查询的断言不会被种子数据干扰 */
    private static final long TEST_SHOW = 3L;

    @Autowired
    private IPostService postService;

    @Autowired
    private IFeedService feedService;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostLikeMapper postLikeMapper;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private ShowMapper showMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(AUTHOR, "19900099101", "测试作者");
        insertUser(VIEWER, "19900099102", "测试观众");
        insertUser(OTHER, "19900099103", "路人");
        insertUserInfo(AUTHOR);
        insertUserInfo(VIEWER);
        insertUserInfo(OTHER);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ------------------------------------------------------------------
    // 详情
    // ------------------------------------------------------------------

    @Test
    void detailAssemblesAuthorImagesShowAndLikedByMe() {
        Post post = insertPost(AUTHOR, 1L, "标题", "正文",
                "https://img/1.png,https://img/2.png", 0, BASE);
        postLikeMapper.insertIgnore(post.getId(), VIEWER);
        post.setLiked(1);
        postMapper.updateById(post);
        Show show = showMapper.selectById(1L);

        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result result = postService.detail(post.getId());

            assertThat(result.getSuccess()).isTrue();
            PostVO vo = (PostVO) result.getData();
            assertThat(vo.getId()).isEqualTo(post.getId());
            assertThat(vo.getUserId()).isEqualTo(AUTHOR);
            assertThat(vo.getNickname()).isEqualTo("测试作者");
            assertThat(vo.getImages()).containsExactly("https://img/1.png", "https://img/2.png");
            assertThat(vo.getShowId()).isEqualTo(1L);
            assertThat(vo.getShowName()).isEqualTo(show.getName());
            assertThat(vo.getShowPoster()).isEqualTo(show.getPoster());
            assertThat(vo.getLiked()).isEqualTo(1);
            assertThat(vo.getLikedByMe()).isTrue();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void detailIsPublicAndLikedByMeFalseForAnonymous() {
        Post post = insertPost(AUTHOR, null, "", "匿名可读", "", 0, BASE);
        postLikeMapper.insertIgnore(post.getId(), VIEWER);

        Result result = postService.detail(post.getId());

        assertThat(result.getSuccess()).isTrue();
        PostVO vo = (PostVO) result.getData();
        assertThat(vo.getLikedByMe()).isFalse();
        assertThat(vo.getImages()).isEmpty();
        assertThat(vo.getShowName()).isNull();
    }

    @Test
    void detailRejectsDeletedPost() {
        Post post = insertPost(AUTHOR, null, "", "已下架", "", 1, BASE);

        Result result = postService.detail(post.getId());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
    }

    @Test
    void detailRejectsMissingPost() {
        Result result = postService.detail(999999999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    void deleteRejectsNonAuthor() {
        Post post = insertPost(AUTHOR, null, "", "别人的动态", "", 0, BASE);

        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result result = postService.delete(post.getId());

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("只能删除自己的动态");
            assertThat(postMapper.selectById(post.getId()).getStatus()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteSoftDeletesAndRemovesFeedMirror() {
        Post post = insertPost(AUTHOR, null, "", "待删除", "", 0, BASE);
        feedService.onPostCreated(post);
        assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).contains(post.getId().toString());
        assertThat(feedMembers(RedisConstants.FEED_USER_KEY + AUTHOR)).contains(post.getId().toString());

        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.delete(post.getId());

            assertThat(result.getSuccess()).isTrue();
            assertThat(postMapper.selectById(post.getId()).getStatus()).isEqualTo(1);
            assertThat(feedMembers(RedisConstants.FEED_GLOBAL_KEY)).doesNotContain(post.getId().toString());
            assertThat(feedMembers(RedisConstants.FEED_USER_KEY + AUTHOR))
                    .doesNotContain(post.getId().toString());
            assertThat(postService.detail(post.getId()).getSuccess()).isFalse();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteRejectsAlreadyDeletedPost() {
        Post post = insertPost(AUTHOR, null, "", "已删除", "", 1, BASE);

        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.delete(post.getId());

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // scroll
    // ------------------------------------------------------------------

    @Test
    void scrollLatestReturnsNewestFirstWithAuthorInfo() {
        Post older = insertPost(AUTHOR, null, "", "较早", "", 0, BASE);
        Post newer = insertPost(AUTHOR, null, "", "较新", "", 0, BASE.plusSeconds(1));
        feedService.onPostCreated(older);
        feedService.onPostCreated(newer);

        Result result = postService.scroll("latest", FeedCursor.empty(), 10);

        assertThat(result.getSuccess()).isTrue();
        PostPageVO page = (PostPageVO) result.getData();
        assertThat(page.getPosts()).extracting(PostVO::getId)
                .containsSubsequence(newer.getId(), older.getId());
        assertThat(page.getPosts().get(0).getNickname()).isEqualTo("测试作者");
        assertThat(page.isFallbackGlobal()).isFalse();
    }

    @Test
    void scrollFollowWithoutFolloweesFallsBackToGlobal() {
        Post post = insertPost(AUTHOR, null, "", "全站可见", "", 0, BASE);
        feedService.onPostCreated(post);

        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result result = postService.scroll("follow", FeedCursor.empty(), 10);

            assertThat(result.getSuccess()).isTrue();
            PostPageVO page = (PostPageVO) result.getData();
            assertThat(page.isFallbackGlobal()).isTrue();
            assertThat(page.getPosts()).extracting(PostVO::getId).contains(post.getId());
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // 按用户 / 按演出
    // ------------------------------------------------------------------

    @Test
    void ofUserPaginatesWithoutDuplicates() {
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            posts.add(insertPost(AUTHOR, null, "", "第 " + i + " 条", "", 0,
                    BASE.plusSeconds(i)));
        }
        insertPost(OTHER, null, "", "别人的", "", 0, BASE.plusSeconds(10));

        PostPageVO page1 = (PostPageVO) postService.ofUser(AUTHOR, FeedCursor.empty(), 2).getData();
        PostPageVO page2 = (PostPageVO) postService.ofUser(AUTHOR, page1.getNextCursor(), 2).getData();
        PostPageVO page3 = (PostPageVO) postService.ofUser(AUTHOR, page2.getNextCursor(), 2).getData();

        List<Long> ids = new ArrayList<>();
        page1.getPosts().forEach(vo -> ids.add(vo.getId()));
        page2.getPosts().forEach(vo -> ids.add(vo.getId()));
        page3.getPosts().forEach(vo -> ids.add(vo.getId()));

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(
                posts.get(4).getId(), posts.get(3).getId(), posts.get(2).getId(),
                posts.get(1).getId(), posts.get(0).getId());
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page2.isHasMore()).isTrue();
        assertThat(page3.isHasMore()).isFalse();
        assertThat(page3.getPosts()).hasSize(1);
    }

    @Test
    void ofUserTieBreaksSameSecondById() {
        Post first = insertPost(AUTHOR, null, "", "同秒 1", "", 0, BASE);
        Post second = insertPost(AUTHOR, null, "", "同秒 2", "", 0, BASE);

        PostPageVO page1 = (PostPageVO) postService.ofUser(AUTHOR, FeedCursor.empty(), 1).getData();
        PostPageVO page2 = (PostPageVO) postService.ofUser(AUTHOR, page1.getNextCursor(), 1).getData();

        assertThat(page1.getPosts()).extracting(PostVO::getId).containsExactly(second.getId());
        assertThat(page2.getPosts()).extracting(PostVO::getId).containsExactly(first.getId());
        assertThat(page1.getNextCursor().getOffset()).isZero();
    }

    @Test
    void ofShowReturnsOnlyActivePostsOfThatShow() {
        Post activeOld = insertPost(AUTHOR, TEST_SHOW, "", "演出 3 旧", "", 0, BASE);
        Post activeNew = insertPost(OTHER, TEST_SHOW, "", "演出 3 新", "", 0, BASE.plusSeconds(1));
        insertPost(AUTHOR, TEST_SHOW, "", "演出 3 已下架", "", 1, BASE.plusSeconds(2));
        insertPost(AUTHOR, 2L, "", "演出 2", "", 0, BASE.plusSeconds(3));

        PostPageVO page = (PostPageVO) postService.ofShow(TEST_SHOW, FeedCursor.empty(), 10).getData();

        assertThat(page.getPosts()).extracting(PostVO::getId)
                .containsExactly(activeNew.getId(), activeOld.getId());
        assertThat(page.isHasMore()).isFalse();
    }

    // ------------------------------------------------------------------
    // 点赞
    // ------------------------------------------------------------------

    @Test
    void toggleLikeThreeTimesReturnsTrueFalseTrueAndKeepsDbConsistent() {
        Post post = insertPost(AUTHOR, null, "", "点赞切换", "", 0, BASE);

        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result first = postService.toggleLike(post.getId());

            assertThat(first.getSuccess()).isTrue();
            PostLikeResultVO r1 = (PostLikeResultVO) first.getData();
            assertThat(r1.getLiked()).isTrue();
            assertThat(r1.getLikedCount()).isEqualTo(1);
            assertThat(postMapper.selectById(post.getId()).getLiked()).isEqualTo(1);
            assertThat(postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, post.getId()))).isEqualTo(1L);
            assertThat(feedMembers(RedisConstants.POST_LIKED_KEY + post.getId()))
                    .contains(String.valueOf(VIEWER));

            Result second = postService.toggleLike(post.getId());

            assertThat(second.getSuccess()).isTrue();
            PostLikeResultVO r2 = (PostLikeResultVO) second.getData();
            assertThat(r2.getLiked()).isFalse();
            assertThat(r2.getLikedCount()).isZero();
            assertThat(postMapper.selectById(post.getId()).getLiked()).isZero();
            assertThat(postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, post.getId()))).isZero();
            assertThat(feedMembers(RedisConstants.POST_LIKED_KEY + post.getId()))
                    .doesNotContain(String.valueOf(VIEWER));

            Result third = postService.toggleLike(post.getId());

            assertThat(third.getSuccess()).isTrue();
            PostLikeResultVO r3 = (PostLikeResultVO) third.getData();
            assertThat(r3.getLiked()).isTrue();
            assertThat(r3.getLikedCount()).isEqualTo(1);
            assertThat(((PostVO) postService.detail(post.getId()).getData()).getLikedByMe()).isTrue();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void toggleLikeRequiresLogin() {
        Post post = insertPost(AUTHOR, null, "", "未登录点赞", "", 0, BASE);

        Result result = postService.toggleLike(post.getId());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
    }

    @Test
    void toggleLikeRejectsDeletedPost() {
        Post post = insertPost(AUTHOR, null, "", "已下架", "", 1, BASE);

        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result result = postService.toggleLike(post.getId());

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void toggleLikeRejectsMissingPost() {
        UserHolder.saveUser(userOf(VIEWER));
        try {
            Result result = postService.toggleLike(999999999L);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void likedUsersReturnsMostRecentFive() {
        Post post = insertPost(AUTHOR, null, "", "点赞 Top5", "", 0, BASE);
        for (int i = 0; i < 6; i++) {
            long userId = 99201L + i;
            insertUser(userId, "199000" + (99201 + i), "点赞用户" + i);
            insertUserInfo(userId);
            postLikeMapper.insert(new PostLike()
                    .setPostId(post.getId())
                    .setUserId(userId)
                    .setCreateTime(BASE.plusSeconds(i)));
        }
        // 模拟 Redis 镜像丢失，验证从 tb_post_like 回填
        stringRedisTemplate.delete(RedisConstants.POST_LIKED_KEY + post.getId());

        Result result = postService.likedUsers(post.getId(), 5);

        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<PostLikeUserVO> users = (List<PostLikeUserVO>) result.getData();
        assertThat(users).extracting(PostLikeUserVO::getUserId)
                .containsExactly(99206L, 99205L, 99204L, 99203L, 99202L);
        assertThat(users.get(0).getNickname()).isEqualTo("点赞用户5");
        assertThat(feedMembers(RedisConstants.POST_LIKED_KEY + post.getId())).hasSize(6);
    }

    @Test
    void likedUsersReturnsEmptyWhenNoLikes() {
        Post post = insertPost(AUTHOR, null, "", "没有点赞", "", 0, BASE);

        Result result = postService.likedUsers(post.getId(), 5);

        assertThat(result.getSuccess()).isTrue();
        assertThat((List<?>) result.getData()).isEmpty();
    }

    @Test
    void likedUsersRejectsMissingPost() {
        Result result = postService.likedUsers(999999999L, 5);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
    }

    // ------------------------------------------------------------------
    // 发布校验
    // ------------------------------------------------------------------

    @Test
    void publishRejectsEmptyContentAndImages() {
        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.publish(dto("", "   ", List.of()));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("正文和图片至少填一项");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsContentOver2000() {
        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.publish(dto("", "字".repeat(2001), List.of()));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("正文不能超过 2000 字");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsTitleOver128() {
        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.publish(dto("标".repeat(129), "正文", List.of()));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("标题不能超过 128 字");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsMoreThanNineImages() {
        UserHolder.saveUser(userOf(AUTHOR));
        try {
            List<String> images = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                images.add("https://img/" + i + ".png");
            }
            Result result = postService.publish(dto("", "", images));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("最多上传 9 张图片");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishAllowsImagesOnly() {
        UserHolder.saveUser(userOf(AUTHOR));
        try {
            Result result = postService.publish(dto("", "", List.of("https://img/1.png")));

            assertThat(result.getSuccess()).isTrue();
            Post saved = postMapper.selectById((Long) result.getData());
            assertThat(saved.getContent()).isEmpty();
            assertThat(saved.getImages()).isEqualTo("https://img/1.png");
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private PostCreateDTO dto(String title, String content, List<String> images) {
        PostCreateDTO dto = new PostCreateDTO();
        dto.setTitle(title);
        dto.setContent(content);
        dto.setImages(images);
        return dto;
    }

    private UserDTO userOf(long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        return user;
    }

    private void insertUser(long id, String phone, String nickname) {
        userMapper.insert(new User()
                .setId(id)
                .setPhone(phone)
                .setPassword("")
                .setNickname(nickname)
                .setIcon("https://img/avatar.png"));
    }

    private void insertUserInfo(long userId) {
        userInfoMapper.insert(new UserInfo()
                .setUserId(userId)
                .setFans(0)
                .setFollowee(0)
                .setCredits(0)
                .setLevel(1)
                .setShowCount(0));
    }

    private Post insertPost(long userId, Long showId, String title, String content,
                            String images, int status, LocalDateTime createTime) {
        Post post = new Post()
                .setUserId(userId)
                .setShowId(showId)
                .setTitle(title)
                .setContent(content)
                .setImages(images)
                .setLiked(0)
                .setComments(0)
                .setStatus(status)
                .setCreateTime(createTime);
        postMapper.insert(post);
        return post;
    }

    private java.util.Set<String> feedMembers(String key) {
        java.util.Set<String> members = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        return members == null ? java.util.Set.of() : members;
    }

    private void clean() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getUserId, TEST_USERS));
        List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
        if (!postIds.isEmpty()) {
            postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                    .in(PostLike::getPostId, postIds));
            Object[] members = postIds.stream().map(String::valueOf).toArray();
            stringRedisTemplate.opsForZSet().remove(RedisConstants.FEED_GLOBAL_KEY, members);
            stringRedisTemplate.delete(postIds.stream()
                    .map(id -> RedisConstants.POST_LIKED_KEY + id)
                    .collect(Collectors.toList()));
            postMapper.deleteByIds(postIds);
        }
        postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                .in(PostLike::getUserId, TEST_USERS));
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .in(Follow::getUserId, TEST_USERS)
                .or()
                .in(Follow::getFollowUserId, TEST_USERS));
        stringRedisTemplate.delete(TEST_USERS.stream()
                .map(id -> RedisConstants.FEED_USER_KEY + id)
                .collect(Collectors.toList()));
        userInfoMapper.deleteByIds(TEST_USERS);
        userMapper.deleteByIds(TEST_USERS);
    }
}
