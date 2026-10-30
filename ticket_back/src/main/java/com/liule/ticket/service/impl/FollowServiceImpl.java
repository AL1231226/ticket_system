package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.FollowListVO;
import com.liule.ticket.dto.FollowResultVO;
import com.liule.ticket.dto.FollowUserVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Follow;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.FollowMapper;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.mapper.UserMapper;
import com.liule.ticket.service.IFeedService;
import com.liule.ticket.service.IFollowService;
import com.liule.ticket.utils.AfterCommit;
import com.liule.ticket.utils.RedisConstants;
import com.liule.ticket.utils.SystemConstants;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关注关系服务（E12-8）。
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow>
        implements IFollowService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private UserInfoMapper userInfoMapper;

    @Resource
    private IFeedService feedService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result toggle(Long targetUserId, boolean follow) {
        UserDTO me = UserHolder.getUser();
        if (me == null || me.getId() == null) {
            return Result.fail("请先登录");
        }
        if (targetUserId == null) {
            return Result.fail("用户不存在");
        }
        if (targetUserId.equals(me.getId())) {
            return Result.fail("不能关注自己");
        }
        User target = userMapper.selectById(targetUserId);
        if (target == null) {
            return Result.fail("用户不存在");
        }

        if (follow) {
            int inserted = baseMapper.insertIgnore(me.getId(), targetUserId);
            if (inserted > 0) {
                userInfoMapper.incrementFollowee(me.getId());
                userInfoMapper.incrementFans(targetUserId);
                Long myId = me.getId();
                // 事务提交后再回填 Feed / 写 Redis，回滚不留下镜像
                AfterCommit.run(() -> {
                    feedService.onFollowed(myId, targetUserId);
                    addFollowMirror(myId, targetUserId);
                });
            }
        } else {
            int deleted = baseMapper.delete(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, me.getId())
                    .eq(Follow::getFollowUserId, targetUserId));
            if (deleted > 0) {
                userInfoMapper.decrementFollowee(me.getId());
                userInfoMapper.decrementFans(targetUserId);
                Long myId = me.getId();
                AfterCommit.run(() -> {
                    feedService.onUnfollowed(myId, targetUserId);
                    removeFollowMirror(myId, targetUserId);
                });
            }
        }

        UserInfo info = userInfoMapper.selectById(targetUserId);
        int fans = info == null || info.getFans() == null ? 0 : info.getFans();
        return Result.ok(new FollowResultVO(follow, fans));
    }

    @Override
    public Result list(Long userId, String type, FeedCursor cursor, int count) {
        if (userId == null) {
            return Result.ok(FollowListVO.empty());
        }
        if (userMapper.selectById(userId) == null) {
            return Result.fail("用户不存在");
        }
        boolean fans = "fans".equalsIgnoreCase(type);
        if (!fans && !"followees".equalsIgnoreCase(type)) {
            return Result.fail("type 只能是 followees 或 fans");
        }

        int size = normalizeCount(count);
        LambdaQueryWrapper<Follow> wrapper = new LambdaQueryWrapper<>();
        if (fans) {
            wrapper.eq(Follow::getFollowUserId, userId);
        } else {
            wrapper.eq(Follow::getUserId, userId);
        }

        // 游标用 follow 行 id + create_time 双列定位；lastId 不属于本列表时从第一页重来
        if (cursor != null && !cursor.isEmpty()) {
            Follow last = getById(cursor.getLastId());
            boolean sameList = last != null && (fans
                    ? userId.equals(last.getFollowUserId())
                    : userId.equals(last.getUserId()));
            if (sameList && last.getCreateTime() != null) {
                LocalDateTime boundary = last.getCreateTime();
                Long lastId = cursor.getLastId();
                wrapper.and(w -> w.lt(Follow::getCreateTime, boundary)
                        .or(q -> q.eq(Follow::getCreateTime, boundary)
                                .lt(Follow::getId, lastId)));
            }
        }

        wrapper.orderByDesc(Follow::getCreateTime)
                .orderByDesc(Follow::getId)
                .last("LIMIT " + (size + 1));

        List<Follow> rows = list(wrapper);
        boolean hasMore = rows.size() > size;
        List<Follow> page = hasMore ? new ArrayList<>(rows.subList(0, size)) : rows;
        FeedCursor next = page.isEmpty()
                ? null
                : FeedCursor.of(page.get(page.size() - 1).getId(), 0);

        List<Long> memberIds = page.stream()
                .map(follow -> fans ? follow.getUserId() : follow.getFollowUserId())
                .distinct()
                .collect(Collectors.toList());
        return Result.ok(new FollowListVO(toFollowUserVOs(memberIds), next, hasMore));
    }

    @Override
    public Result common(Long targetUserId) {
        UserDTO me = UserHolder.getUser();
        if (me == null || me.getId() == null) {
            return Result.fail("请先登录");
        }
        if (targetUserId == null || userMapper.selectById(targetUserId) == null) {
            return Result.fail("用户不存在");
        }

        List<Long> ids = commonIds(me.getId(), targetUserId);
        return Result.ok(toFollowUserVOs(ids));
    }

    @Override
    public boolean isFollowing(Long userId, Long targetUserId) {
        if (userId == null || targetUserId == null || userId.equals(targetUserId)) {
            return false;
        }
        return baseMapper.selectCount(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, targetUserId)) > 0;
    }

    /**
     * 共同关注：优先 SINTER 两个关注 Set；key 缺失先按 tb_follow 回填，
     * Redis 不可用或回填失败直接回退 SQL。
     */
    private List<Long> commonIds(Long me, Long targetUserId) {
        String myKey = RedisConstants.FOLLOW_USER_KEY + me;
        String targetKey = RedisConstants.FOLLOW_USER_KEY + targetUserId;
        try {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(myKey))) {
                backfillFollowSet(me);
            }
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(targetKey))) {
                backfillFollowSet(targetUserId);
            }
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(myKey))
                    && Boolean.TRUE.equals(stringRedisTemplate.hasKey(targetKey))) {
                Set<String> intersection = stringRedisTemplate.opsForSet()
                        .intersect(myKey, targetKey);
                if (intersection != null) {
                    return intersection.stream()
                            .map(Long::valueOf)
                            .sorted(Comparator.reverseOrder())
                            .limit(SystemConstants.FOLLOW_COMMON_MAX)
                            .collect(Collectors.toList());
                }
            }
        } catch (Exception e) {
            log.warn("共同关注读取 Redis 失败，回退 DB，me={}, target={}", me, targetUserId, e);
        }

        return baseMapper.selectCommon(me, targetUserId).stream()
                .sorted(Comparator.reverseOrder())
                .limit(SystemConstants.FOLLOW_COMMON_MAX)
                .collect(Collectors.toList());
    }

    /** 用户卡片批量装配：昵称/头像来自 tb_user，简介来自 tb_user_info */
    private List<FollowUserVO> toFollowUserVOs(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getId, userIds))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, UserInfo> infos = userInfoMapper.selectList(new LambdaQueryWrapper<UserInfo>()
                        .in(UserInfo::getUserId, userIds))
                .stream()
                .collect(Collectors.toMap(UserInfo::getUserId, Function.identity()));

        List<FollowUserVO> result = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            User user = users.get(userId);
            if (user == null) {
                continue;
            }
            FollowUserVO vo = new FollowUserVO();
            vo.setUserId(userId);
            vo.setNickname(user.getNickname());
            vo.setIcon(user.getIcon());
            UserInfo info = infos.get(userId);
            if (info != null) {
                vo.setIntroduce(info.getIntroduce());
            }
            result.add(vo);
        }
        return result;
    }

    private int normalizeCount(int count) {
        if (count <= 0) {
            return 10;
        }
        return Math.min(count, SystemConstants.FOLLOW_LIST_MAX);
    }

    /** 关注集合缺失时整体回填，避免只 SADD 一条留下残缺镜像 */
    private void addFollowMirror(Long userId, Long targetUserId) {
        try {
            String key = RedisConstants.FOLLOW_USER_KEY + userId;
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                backfillFollowSet(userId);
                return;
            }
            stringRedisTemplate.opsForSet().add(key, String.valueOf(targetUserId));
        } catch (Exception e) {
            log.warn("关注集合写入失败，userId={}, targetUserId={}", userId, targetUserId, e);
        }
    }

    private void removeFollowMirror(Long userId, Long targetUserId) {
        try {
            String key = RedisConstants.FOLLOW_USER_KEY + userId;
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                backfillFollowSet(userId);
                return;
            }
            stringRedisTemplate.opsForSet().remove(key, String.valueOf(targetUserId));
        } catch (Exception e) {
            log.warn("关注集合删除失败，userId={}, targetUserId={}", userId, targetUserId, e);
        }
    }

    private void backfillFollowSet(Long userId) {
        try {
            List<Follow> rows = list(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId));
            String key = RedisConstants.FOLLOW_USER_KEY + userId;
            stringRedisTemplate.delete(key);
            if (rows.isEmpty()) {
                return;
            }
            String[] members = rows.stream()
                    .map(follow -> String.valueOf(follow.getFollowUserId()))
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(key, members);
        } catch (Exception e) {
            log.warn("关注集合回填失败，userId={}", userId, e);
        }
    }
}
