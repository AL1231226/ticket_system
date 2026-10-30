package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FeedSlice;
import com.liule.ticket.dto.PostCreateDTO;
import com.liule.ticket.dto.PostLikeResultVO;
import com.liule.ticket.dto.PostLikeUserVO;
import com.liule.ticket.dto.PostPageVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.PostLike;
import com.liule.ticket.entity.User;
import com.liule.ticket.mapper.PostLikeMapper;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserMapper;
import com.liule.ticket.service.IFeedService;
import com.liule.ticket.service.IPostService;
import com.liule.ticket.service.PostAssembler;
import com.liule.ticket.utils.AfterCommit;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.SystemConstants;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 社区动态服务。
 * <p>
 * 实现发布、详情、软删除、Feed / 按用户 / 按演出的游标查询，以及点赞切换与 Top5；
 * 评论留到 E12-7。
 */
@Slf4j
@Service
public class PostServiceImpl extends ServiceImpl<PostMapper, Post> implements IPostService {

    @Resource
    private IFeedService feedService;

    @Resource
    private PostAssembler postAssembler;

    @Resource
    private PostLikeMapper postLikeMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result publish(PostCreateDTO dto) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录再发布动态");
        }
        if (dto == null) {
            return Result.fail("发布内容不能为空");
        }

        String title = dto.getTitle() == null ? "" : dto.getTitle().trim();
        String content = dto.getContent() == null ? "" : dto.getContent().trim();
        List<String> images = dto.getImages() == null
                ? List.of()
                : dto.getImages().stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());

        if (content.isEmpty() && images.isEmpty()) {
            return Result.fail("正文和图片至少填一项");
        }
        if (content.length() > 2000) {
            return Result.fail("正文不能超过 2000 字");
        }
        if (title.length() > 128) {
            return Result.fail("标题不能超过 128 字");
        }
        if (images.size() > 9) {
            return Result.fail("最多上传 9 张图片");
        }

        Post post = new Post()
                .setUserId(user.getId())
                .setTitle(title)
                .setContent(content)
                .setShowId(dto.getShowId())
                .setSessionId(dto.getSessionId())
                .setImages(images.isEmpty() ? "" : String.join(",", images))
                .setCreateTime(LocalDateTime.now());

        save(post);
        // 事务提交后再写 Redis：回滚不产生 Feed，Redis 失败只记日志
        AfterCommit.run(() -> feedService.onPostCreated(post));
        return Result.ok(post.getId());
    }

    @Override
    public Result detail(Long postId) {
        Post post = postId == null ? null : getById(postId);
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }
        return Result.ok(postAssembler.toVOList(List.of(post), currentUserId()).get(0));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result delete(Long postId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Post post = postId == null ? null : getById(postId);
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }
        if (!user.getId().equals(post.getUserId())) {
            return Result.fail("只能删除自己的动态");
        }

        updateById(new Post().setId(postId).setStatus(1));
        // 粉丝 Feed 保留 id，读取时按 status 过滤；全站和作者 Feed 直接移除
        AfterCommit.run(() -> feedService.onPostDeleted(post));
        return Result.ok(postId);
    }

    @Override
    public Result scroll(String mode, FeedCursor cursor, int count) {
        Long userId = currentUserId();
        FeedSlice slice = "follow".equalsIgnoreCase(mode) && userId != null
                ? feedService.readFollow(userId, cursor, count)
                : feedService.readGlobal(cursor, count);

        PostPageVO page = new PostPageVO(
                postAssembler.toVOList(slice.getPosts(), userId),
                slice.getNextCursor(),
                slice.isHasMore(),
                slice.isFallbackGlobal());
        return Result.ok(page);
    }

    @Override
    public Result ofUser(Long userId, FeedCursor cursor, int count) {
        if (userId == null) {
            return Result.ok(PostPageVO.empty());
        }
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId);
        return Result.ok(queryPage(wrapper, cursor, count));
    }

    @Override
    public Result ofShow(Long showId, FeedCursor cursor, int count) {
        if (showId == null) {
            return Result.ok(PostPageVO.empty());
        }
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .eq(Post::getShowId, showId);
        return Result.ok(queryPage(wrapper, cursor, count));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result toggleLike(Long postId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return Result.fail("请先登录");
        }
        Post post = postId == null ? null : getById(postId);
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }

        boolean liked;
        // 唯一索引是幂等核心：插入成功=点赞，插入 0 行=已点过，改走取消
        int inserted = postLikeMapper.insertIgnore(postId, user.getId());
        if (inserted > 0) {
            // 点赞数 +1
            baseMapper.incrementLiked(postId);
            liked = true;
            // 加入点赞队列
            AfterCommit.run(() -> addLikedMirror(postId, user.getId()));
        } else {
            int deleted = postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, postId)
                    .eq(PostLike::getUserId, user.getId()));
            if (deleted > 0) {
                baseMapper.decrementLiked(postId);
            }
            liked = false;
            if (deleted > 0) {
                //移除点赞队列
                AfterCommit.run(() -> removeLikedMirror(postId, user.getId()));
            }
        }

        Post updated = getById(postId);
        int likedCount = updated == null || updated.getLiked() == null ? 0 : updated.getLiked();
        return Result.ok(new PostLikeResultVO(liked, likedCount));
    }

    @Override
    public Result likedUsers(Long postId, int limit) {
        Post post = postId == null ? null : getById(postId);
        if (post == null || !Integer.valueOf(0).equals(post.getStatus())) {
            return Result.fail("动态不存在");
        }

        int size = normalizeLikeLimit(limit);
        List<Long> userIds = likedUserIds(postId, size);
        if (userIds.isEmpty()) {
            return Result.ok(List.of());
        }

        Map<Long, User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getId, userIds))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<PostLikeUserVO> result = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            PostLikeUserVO vo = new PostLikeUserVO();
            vo.setUserId(userId);
            User user = users.get(userId);
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setIcon(user.getIcon());
            }
            result.add(vo);
        }
        return Result.ok(result);
    }

    /**
     * 最近点赞用户 id，按点赞时间倒序。
     * <p>
     * Redis ZSet 只是镜像：key 不存在时从 tb_post_like 回填；
     * Redis 故障直接回退 DB，保证接口可用。
     */
    private List<Long> likedUserIds(Long postId, int size) {
        String key = RedisConstants.POST_LIKED_KEY + postId;
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                backfillLikedMirror(postId);
            }
            Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, size - 1);
            if (tuples != null && !tuples.isEmpty()) {
                return tuples.stream()
                        .map(ZSetOperations.TypedTuple::getValue)
                        .filter(Objects::nonNull)
                        .map(Long::valueOf)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("点赞用户读取 Redis 失败，回退 DB，postId={}", postId, e);
        }
        return postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getPostId, postId)
                        .orderByDesc(PostLike::getCreateTime)
                        .orderByDesc(PostLike::getId)
                        .last("LIMIT " + size))
                .stream()
                .map(PostLike::getUserId)
                .collect(Collectors.toList());
    }

    private void backfillLikedMirror(Long postId) {
        List<PostLike> recent = postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)
                .orderByDesc(PostLike::getCreateTime)
                .orderByDesc(PostLike::getId)
                .last("LIMIT " + SystemConstants.POST_LIKED_MAX));
        if (recent.isEmpty()) {
            return;
        }
        String key = RedisConstants.POST_LIKED_KEY + postId;
        for (PostLike like : recent) {
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(like.getUserId()),
                    scoreOf(like.getCreateTime()));
        }
        trimLikedMirror(key);
    }

    private void addLikedMirror(Long postId, Long userId) {
        try {
            String key = RedisConstants.POST_LIKED_KEY + postId;
            stringRedisTemplate.opsForZSet().add(key, String.valueOf(userId),
                    System.currentTimeMillis());
            trimLikedMirror(key);
        } catch (Exception e) {
            log.warn("点赞镜像写入失败，postId={}, userId={}", postId, userId, e);
        }
    }

    private void removeLikedMirror(Long postId, Long userId) {
        try {
            stringRedisTemplate.opsForZSet()
                    .remove(RedisConstants.POST_LIKED_KEY + postId, String.valueOf(userId));
        } catch (Exception e) {
            log.warn("点赞镜像删除失败，postId={}, userId={}", postId, userId, e);
        }
    }

    private void trimLikedMirror(String key) {
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size != null && size > SystemConstants.POST_LIKED_MAX) {
            stringRedisTemplate.opsForZSet()
                    .removeRange(key, 0, size - SystemConstants.POST_LIKED_MAX - 1);
        }
    }

    private long scoreOf(LocalDateTime time) {
        return time == null
                ? System.currentTimeMillis()
                : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private int normalizeLikeLimit(int limit) {
        if (limit <= 0) {
            return SystemConstants.POST_LIKED_TOP_N;
        }
        return Math.min(limit, SystemConstants.POST_LIKED_TOP_MAX);
    }

    /**
     * 按 (create_time, id) 倒序取一页。
     * <p>
     * 游标用上一页最后一条的发布时间 + id 做双列定位，同一秒内的动态也不会
     * 重复或漏页，所以这里的 nextCursor.offset 恒为 0。lastId 被硬删时
     * 退化为 offset 绝对跳过，和 Feed 读取的回退策略保持一致。
     */
    private PostPageVO queryPage(LambdaQueryWrapper<Post> wrapper, FeedCursor cursor, int count) {
        int size = normalizeCount(count);
        int skip = 0;

        if (cursor != null && !cursor.isEmpty()) {
            Post last = getById(cursor.getLastId());
            if (last != null && last.getCreateTime() != null) {
                LocalDateTime boundary = last.getCreateTime();
                Long lastId = cursor.getLastId();
                // 找到发布时间更早或发布时间相同但 id 更小的动态
                wrapper.and(w -> w.lt(Post::getCreateTime, boundary)
                        .or(q -> q.eq(Post::getCreateTime, boundary).lt(Post::getId, lastId)));
            } else {
                skip = cursor.getOffset() == null ? 0 : Math.max(0, cursor.getOffset());
            }
        }

        wrapper.eq(Post::getStatus, 0)
                .orderByDesc(Post::getCreateTime)
                .orderByDesc(Post::getId)
                .last("LIMIT " + (skip > 0 ? skip + ", " : "") + (size + 1));

        List<Post> rows = list(wrapper);
        boolean hasMore = rows.size() > size;
        List<Post> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;
        FeedCursor next = page.isEmpty()
                ? null
                : FeedCursor.of(page.get(page.size() - 1).getId(), 0);

        return new PostPageVO(
                postAssembler.toVOList(page, currentUserId()), next, hasMore, false);
    }

    private int normalizeCount(int count) {
        if (count <= 0) {
            return 10;
        }
        return Math.min(count, 50);
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }
}
