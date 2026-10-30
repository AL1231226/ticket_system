package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.dto.UserProfileVO;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.mapper.UserMapper;
import com.liule.ticket.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E12-9 公开主页的行为验证。
 * <p>
 * 真实 MySQL，用 99501–99503 这组专用测试用户，不碰种子用户；
 * 每个用例前后清掉测试用户、资料、动态与关注。
 */
@SpringBootTest
class UserServiceImplTest {

    private static final long ME = 99501L;
    private static final long TARGET = 99502L;
    private static final long OTHER = 99503L;
    private static final List<Long> TEST_USERS = Arrays.asList(ME, TARGET, OTHER);

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Autowired
    private IUserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserInfoMapper userInfoMapper;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private FollowMapper followMapper;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(ME, "19900099501", "访客");
        insertUser(TARGET, "19900099502", "主页用户");
        insertUser(OTHER, "19900099503", "路人");
        insertUserInfo(ME, 0, 1);
        insertUserInfo(TARGET, 7, 4);
        insertUserInfo(OTHER, 0, 0);
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    @Test
    void profileReturnsPublicInfoAndCounts() {
        insertPost(TARGET, 0, BASE);
        insertPost(TARGET, 0, BASE.plusSeconds(1));
        insertPost(TARGET, 1, BASE.plusSeconds(2));
        insertPost(ME, 0, BASE.plusSeconds(3));

        Result result = userService.queryPublicProfile(TARGET);

        assertThat(result.getSuccess()).isTrue();
        UserProfileVO vo = (UserProfileVO) result.getData();
        assertThat(vo.getUserId()).isEqualTo(TARGET);
        assertThat(vo.getNickname()).isEqualTo("主页用户");
        assertThat(vo.getIcon()).isEqualTo("https://img/avatar.png");
        assertThat(vo.getIntroduce()).isEqualTo("intro-99502");
        assertThat(vo.getCity()).isEqualTo("上海");
        assertThat(vo.getGender()).isEqualTo(2);
        assertThat(vo.getLevel()).isEqualTo(3);
        assertThat(vo.getShowCount()).isEqualTo(12);
        assertThat(vo.getFans()).isEqualTo(7);
        assertThat(vo.getFollowee()).isEqualTo(4);
        assertThat(vo.getPosts()).isEqualTo(2L);
        assertThat(vo.getIsFollowing()).isFalse();
    }

    @Test
    void profileShowsFollowingForLoggedIn() {
        insertFollow(ME, TARGET);

        UserHolder.saveUser(userOf(ME));
        try {
            UserProfileVO vo = (UserProfileVO) userService.queryPublicProfile(TARGET).getData();

            assertThat(vo.getIsFollowing()).isTrue();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void profileShowsNotFollowingWhenRelationMissing() {
        UserHolder.saveUser(userOf(ME));
        try {
            UserProfileVO vo = (UserProfileVO) userService.queryPublicProfile(TARGET).getData();

            assertThat(vo.getIsFollowing()).isFalse();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void profileOfSelfReturnsIsFollowingFalse() {
        insertFollow(ME, TARGET);

        UserHolder.saveUser(userOf(TARGET));
        try {
            UserProfileVO vo = (UserProfileVO) userService.queryPublicProfile(TARGET).getData();

            assertThat(vo.getIsFollowing()).isFalse();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void profileRejectsMissingUser() {
        Result result = userService.queryPublicProfile(999999999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("用户不存在");
    }

    @Test
    void profileRejectsNullUser() {
        Result result = userService.queryPublicProfile(null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("用户不存在");
    }

    @Test
    void profileToleratesMissingUserInfo() {
        userInfoMapper.deleteById(TARGET);
        insertPost(TARGET, 0, BASE);

        Result result = userService.queryPublicProfile(TARGET);

        assertThat(result.getSuccess()).isTrue();
        UserProfileVO vo = (UserProfileVO) result.getData();
        assertThat(vo.getNickname()).isEqualTo("主页用户");
        assertThat(vo.getIntroduce()).isEmpty();
        assertThat(vo.getCity()).isEmpty();
        assertThat(vo.getGender()).isZero();
        assertThat(vo.getLevel()).isEqualTo(1);
        assertThat(vo.getShowCount()).isZero();
        assertThat(vo.getFans()).isZero();
        assertThat(vo.getFollowee()).isZero();
        assertThat(vo.getPosts()).isEqualTo(1L);
        assertThat(vo.getIsFollowing()).isFalse();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

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

    private void insertUserInfo(long userId, int fans, int followee) {
        userInfoMapper.insert(new UserInfo()
                .setUserId(userId)
                .setCity("上海")
                .setIntroduce("intro-" + userId)
                .setGender(2)
                .setFans(fans)
                .setFollowee(followee)
                .setCredits(100)
                .setLevel(3)
                .setShowCount(12));
    }

    private Post insertPost(long userId, int status, LocalDateTime createTime) {
        Post post = new Post()
                .setUserId(userId)
                .setTitle("")
                .setContent("测试动态")
                .setImages("")
                .setLiked(0)
                .setComments(0)
                .setStatus(status)
                .setCreateTime(createTime);
        postMapper.insert(post);
        return post;
    }

    private void insertFollow(long userId, long followUserId) {
        followMapper.insert(new Follow()
                .setUserId(userId)
                .setFollowUserId(followUserId)
                .setCreateTime(BASE));
    }

    private void clean() {
        followMapper.delete(new LambdaQueryWrapper<Follow>()
                .in(Follow::getUserId, TEST_USERS)
                .or()
                .in(Follow::getFollowUserId, TEST_USERS));
        postMapper.delete(new LambdaQueryWrapper<Post>()
                .in(Post::getUserId, TEST_USERS));
        userInfoMapper.deleteByIds(TEST_USERS);
        userMapper.deleteByIds(TEST_USERS);
    }
}
