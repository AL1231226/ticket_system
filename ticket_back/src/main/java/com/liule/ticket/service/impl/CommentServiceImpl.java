package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
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
import com.liule.ticket.service.ICommentService;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动态评论服务（E12-7）。
 */
@Service
public class CommentServiceImpl extends ServiceImpl<PostCommentMapper, PostComment>
        implements ICommentService {

    @Resource
    private PostMapper postMapper;

    @Resource
    private UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result publish(CommentCreateDTO dto) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        String content = dto == null || dto.getContent() == null ? "" : dto.getContent().trim();
        if (content.isEmpty()) {
            return Result.fail("评论内容不能为空");
        }
        if (content.length() > 512) {
            return Result.fail("评论不能超过 512 字");
        }
        Post post = dto.getPostId() == null ? null : postMapper.selectById(dto.getPostId());
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }

        PostComment comment = new PostComment()
                .setPostId(post.getId())
                .setUserId(user.getId())
                .setParentId(0L)
                .setAnswerId(0L)
                .setContent(content)
                .setLiked(0)
                .setStatus(0)
                .setCreateTime(LocalDateTime.now());
        save(comment);
        postMapper.incrementComments(post.getId());
        return Result.ok(toVO(comment));
    }

    @Override
    public Result ofPost(Long postId, FeedCursor cursor, int count) {
        Post post = postId == null ? null : postMapper.selectById(postId);
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }

        int size = normalizeCount(count);
        int skip = 0;
        LambdaQueryWrapper<PostComment> wrapper = new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId);

        // 游标沿用动态列表的 (create_time, id) 双列定位：同秒评论按 id 倒序，
        // lastId 被硬删或不属于本动态时退化为 offset 绝对跳过。
        if (cursor != null && !cursor.isEmpty()) {
            PostComment last = getById(cursor.getLastId());
            if (last != null && postId.equals(last.getPostId()) && last.getCreateTime() != null) {
                LocalDateTime boundary = last.getCreateTime();
                Long lastId = cursor.getLastId();
                wrapper.and(w -> w.lt(PostComment::getCreateTime, boundary)
                        .or(q -> q.eq(PostComment::getCreateTime, boundary)
                                .lt(PostComment::getId, lastId)));
            } else {
                skip = cursor.getOffset() == null ? 0 : Math.max(0, cursor.getOffset());
            }
        }

        wrapper.eq(PostComment::getStatus, 0)
                .orderByDesc(PostComment::getCreateTime)
                .orderByDesc(PostComment::getId)
                .last("LIMIT " + (skip > 0 ? skip + ", " : "") + (size + 1));

        List<PostComment> rows = list(wrapper);
        boolean hasMore = rows.size() > size;
        List<PostComment> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;
        FeedCursor next = page.isEmpty()
                ? null
                : FeedCursor.of(page.get(page.size() - 1).getId(), 0);

        return Result.ok(new CommentPageVO(toVOList(page), next, hasMore));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result delete(Long commentId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        PostComment comment = commentId == null ? null : getById(commentId);
        if (comment == null || !Integer.valueOf(0).equals(comment.getStatus())) {
            return Result.fail("评论不存在");
        }
        if (!user.getId().equals(comment.getUserId())) {
            return Result.fail("无权删除他人评论");
        }

        updateById(new PostComment().setId(commentId).setStatus(1));
        postMapper.decrementComments(comment.getPostId());
        return Result.ok(commentId);
    }

    private CommentVO toVO(PostComment comment) {
        return toVOList(List.of(comment)).get(0);
    }

    /** 评论人按 id 集合批量查，列表页不允许逐条回源 */
    private List<CommentVO> toVOList(List<PostComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = comments.stream()
                .map(PostComment::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<CommentVO> result = new ArrayList<>(comments.size());
        for (PostComment comment : comments) {
            CommentVO vo = new CommentVO();
            vo.setId(comment.getId());
            vo.setPostId(comment.getPostId());
            vo.setUserId(comment.getUserId());
            User user = users.get(comment.getUserId());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setIcon(user.getIcon());
            }
            vo.setContent(comment.getContent());
            vo.setCreateTime(comment.getCreateTime());
            result.add(vo);
        }
        return result;
    }

    private int normalizeCount(int count) {
        if (count <= 0) {
            return 10;
        }
        return Math.min(count, 50);
    }
}
