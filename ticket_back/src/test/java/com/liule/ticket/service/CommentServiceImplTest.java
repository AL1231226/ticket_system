package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.CommentCreateDTO;
import com.liule.ticket.dto.CommentPageVO;
import com.liule.ticket.dto.CommentVO;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.PostComment;
import com.liule.ticket.entity.User;
import com.liule.ticket.mapper.PostCommentMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserMapper;
import com.liule.ticket.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E12-7 一级评论的行为验证。
 * <p>
 * 沿用 PostServiceImplTest 的方式：真实 MySQL，用 99301–99303 这组专用
 * 测试用户，不碰种子用户；每个用例前后清掉测试动态与评论。
 */
@SpringBootTest
class CommentServiceImplTest {

    private static final long POST_AUTHOR = 99301L;
    private static final long COMMENTER = 99302L;
    private static final long OTHER = 99303L;
    private static final List<Long> TEST_USERS = Arrays.asList(
            POST_AUTHOR, COMMENTER, OTHER);

    /** 用整秒时间，避免 MySQL TIMESTAMP 的小数截断影响游标定位 */
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Autowired
    private ICommentService commentService;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostCommentMapper postCommentMapper;

    @Autowired
    private UserMapper userMapper;

    @BeforeEach
    void setUp() {
        clean();
        insertUser(POST_AUTHOR, "19900099301", "动态作者");
        insertUser(COMMENTER, "19900099302", "评论者");
        insertUser(OTHER, "19900099303", "路人");
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    // ------------------------------------------------------------------
    // 发布
    // ------------------------------------------------------------------

    @Test
    void publishSavesCommentAndIncrementsPostCommentCount() {
        Post post = insertPost(0, BASE);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.publish(dto(post.getId(), "  好看到爆  "));

            assertThat(result.getSuccess()).isTrue();
            CommentVO vo = (CommentVO) result.getData();
            assertThat(vo.getId()).isNotNull();
            assertThat(vo.getPostId()).isEqualTo(post.getId());
            assertThat(vo.getUserId()).isEqualTo(COMMENTER);
            assertThat(vo.getContent()).isEqualTo("好看到爆");
            assertThat(vo.getNickname()).isEqualTo("评论者");
            assertThat(vo.getIcon()).isEqualTo("https://img/avatar.png");

            PostComment saved = postCommentMapper.selectById(vo.getId());
            assertThat(saved.getParentId()).isZero();
            assertThat(saved.getAnswerId()).isZero();
            assertThat(saved.getLiked()).isZero();
            assertThat(saved.getStatus()).isZero();
            assertThat(saved.getCreateTime()).isNotNull();
            assertThat(postMapper.selectById(post.getId()).getComments()).isEqualTo(1);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRequiresLogin() {
        Post post = insertPost(0, BASE);

        Result result = commentService.publish(dto(post.getId(), "未登录评论"));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        assertThat(postMapper.selectById(post.getId()).getComments()).isZero();
        assertThat(commentsOf(post.getId())).isEmpty();
    }

    @Test
    void publishRejectsBlankContent() {
        Post post = insertPost(0, BASE);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.publish(dto(post.getId(), "   "));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("评论内容不能为空");
            assertThat(postMapper.selectById(post.getId()).getComments()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsContentOver512() {
        Post post = insertPost(0, BASE);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.publish(dto(post.getId(), "字".repeat(513)));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("评论不能超过 512 字");
            assertThat(postMapper.selectById(post.getId()).getComments()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsMissingPost() {
        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.publish(dto(999999999L, "动态不存在"));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void publishRejectsDeletedPost() {
        Post post = insertPost(1, BASE);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.publish(dto(post.getId(), "已下架动态"));

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
            assertThat(commentsOf(post.getId())).isEmpty();
        } finally {
            UserHolder.removeUser();
        }
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    @Test
    void ofPostReturnsNewestFirstWithAuthorInfo() {
        Post post = insertPost(0, BASE);
        PostComment older = insertComment(post.getId(), COMMENTER, "较早", 0, BASE);
        PostComment newer = insertComment(post.getId(), OTHER, "较新", 0, BASE.plusSeconds(1));

        Result result = commentService.ofPost(post.getId(), FeedCursor.empty(), 10);

        assertThat(result.getSuccess()).isTrue();
        CommentPageVO page = (CommentPageVO) result.getData();
        assertThat(page.getComments()).extracting(CommentVO::getId)
                .containsExactly(newer.getId(), older.getId());
        assertThat(page.getComments().get(0).getNickname()).isEqualTo("路人");
        assertThat(page.getComments().get(1).getNickname()).isEqualTo("评论者");
        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getNextCursor()).isNotNull();
    }

    @Test
    void ofPostPaginatesWithoutDuplicatesOrSkips() {
        Post post = insertPost(0, BASE);
        List<PostComment> comments = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            comments.add(insertComment(post.getId(), COMMENTER, "第 " + i + " 条", 0,
                    BASE.plusSeconds(i)));
        }

        CommentPageVO page1 = (CommentPageVO) commentService
                .ofPost(post.getId(), FeedCursor.empty(), 2).getData();
        CommentPageVO page2 = (CommentPageVO) commentService
                .ofPost(post.getId(), page1.getNextCursor(), 2).getData();
        CommentPageVO page3 = (CommentPageVO) commentService
                .ofPost(post.getId(), page2.getNextCursor(), 2).getData();

        List<Long> ids = new ArrayList<>();
        page1.getComments().forEach(vo -> ids.add(vo.getId()));
        page2.getComments().forEach(vo -> ids.add(vo.getId()));
        page3.getComments().forEach(vo -> ids.add(vo.getId()));

        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).containsExactly(
                comments.get(4).getId(), comments.get(3).getId(), comments.get(2).getId(),
                comments.get(1).getId(), comments.get(0).getId());
        assertThat(page1.isHasMore()).isTrue();
        assertThat(page2.isHasMore()).isTrue();
        assertThat(page3.isHasMore()).isFalse();
        assertThat(page3.getComments()).hasSize(1);
    }

    @Test
    void ofPostTieBreaksSameSecondById() {
        Post post = insertPost(0, BASE);
        PostComment first = insertComment(post.getId(), COMMENTER, "同秒 1", 0, BASE);
        PostComment second = insertComment(post.getId(), COMMENTER, "同秒 2", 0, BASE);

        CommentPageVO page1 = (CommentPageVO) commentService
                .ofPost(post.getId(), FeedCursor.empty(), 1).getData();
        CommentPageVO page2 = (CommentPageVO) commentService
                .ofPost(post.getId(), page1.getNextCursor(), 1).getData();

        assertThat(page1.getComments()).extracting(CommentVO::getId)
                .containsExactly(second.getId());
        assertThat(page2.getComments()).extracting(CommentVO::getId)
                .containsExactly(first.getId());
        assertThat(page1.getNextCursor().getOffset()).isZero();
    }

    @Test
    void ofPostFiltersDeletedComments() {
        Post post = insertPost(0, BASE);
        PostComment active = insertComment(post.getId(), COMMENTER, "正常", 0, BASE);
        insertComment(post.getId(), COMMENTER, "已删除", 1, BASE.plusSeconds(1));

        CommentPageVO page = (CommentPageVO) commentService
                .ofPost(post.getId(), FeedCursor.empty(), 10).getData();

        assertThat(page.getComments()).extracting(CommentVO::getId)
                .containsExactly(active.getId());
    }

    @Test
    void ofPostRejectsMissingPost() {
        Result result = commentService.ofPost(999999999L, FeedCursor.empty(), 10);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("动态不存在");
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    void deleteSoftDeletesAndDecrementsPostCommentCount() {
        Post post = insertPost(0, BASE);
        PostComment comment = insertComment(post.getId(), COMMENTER, "待删除", 0, BASE);
        post.setComments(1);
        postMapper.updateById(post);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.delete(comment.getId());

            assertThat(result.getSuccess()).isTrue();
            assertThat(postCommentMapper.selectById(comment.getId()).getStatus()).isEqualTo(1);
            assertThat(postMapper.selectById(post.getId()).getComments()).isZero();
            CommentPageVO page = (CommentPageVO) commentService
                    .ofPost(post.getId(), FeedCursor.empty(), 10).getData();
            assertThat(page.getComments()).isEmpty();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteRejectsNonAuthor() {
        Post post = insertPost(0, BASE);
        PostComment comment = insertComment(post.getId(), COMMENTER, "别人的评论", 0, BASE);
        post.setComments(1);
        postMapper.updateById(post);

        UserHolder.saveUser(userOf(OTHER));
        try {
            Result result = commentService.delete(comment.getId());

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("无权删除他人评论");
            assertThat(postCommentMapper.selectById(comment.getId()).getStatus()).isZero();
            assertThat(postMapper.selectById(post.getId()).getComments()).isEqualTo(1);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteRejectsMissingComment() {
        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.delete(999999999L);

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("评论不存在");
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteRejectsAlreadyDeletedComment() {
        Post post = insertPost(0, BASE);
        PostComment comment = insertComment(post.getId(), COMMENTER, "已删除", 1, BASE);

        UserHolder.saveUser(userOf(COMMENTER));
        try {
            Result result = commentService.delete(comment.getId());

            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getErrorMsg()).isEqualTo("评论不存在");
            assertThat(postMapper.selectById(post.getId()).getComments()).isZero();
        } finally {
            UserHolder.removeUser();
        }
    }

    @Test
    void deleteRequiresLogin() {
        Post post = insertPost(0, BASE);
        PostComment comment = insertComment(post.getId(), COMMENTER, "未登录删除", 0, BASE);

        Result result = commentService.delete(comment.getId());

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("请先登录");
        assertThat(postCommentMapper.selectById(comment.getId()).getStatus()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private CommentCreateDTO dto(Long postId, String content) {
        CommentCreateDTO dto = new CommentCreateDTO();
        dto.setPostId(postId);
        dto.setContent(content);
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

    private Post insertPost(int status, LocalDateTime createTime) {
        Post post = new Post()
                .setUserId(POST_AUTHOR)
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

    private PostComment insertComment(long postId, long userId, String content,
                                      int status, LocalDateTime createTime) {
        PostComment comment = new PostComment()
                .setPostId(postId)
                .setUserId(userId)
                .setParentId(0L)
                .setAnswerId(0L)
                .setContent(content)
                .setLiked(0)
                .setStatus(status)
                .setCreateTime(createTime);
        postCommentMapper.insert(comment);
        return comment;
    }

    private List<PostComment> commentsOf(long postId) {
        return postCommentMapper.selectList(new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId));
    }

    private void clean() {
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .in(Post::getUserId, TEST_USERS));
        List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
        if (!postIds.isEmpty()) {
            postCommentMapper.delete(new LambdaQueryWrapper<PostComment>()
                    .in(PostComment::getPostId, postIds));
            postMapper.deleteByIds(postIds);
        }
        postCommentMapper.delete(new LambdaQueryWrapper<PostComment>()
                .in(PostComment::getUserId, TEST_USERS));
        userMapper.deleteByIds(TEST_USERS);
    }
}
