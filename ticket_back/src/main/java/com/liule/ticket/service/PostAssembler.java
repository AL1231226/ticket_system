package com.liule.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liule.ticket.dto.PostVO;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.PostLike;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.User;
import com.liule.ticket.mapper.PostLikeMapper;
import com.liule.ticket.mapper.ShowMapper;
import com.liule.ticket.mapper.UserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 动态展示对象装配。
 * <p>
 * 作者、演出、当前用户是否点赞都按 id 集合批量查，列表页不允许逐条回源。
 */
@Component
public class PostAssembler {

    @Resource
    private UserMapper userMapper;

    @Resource
    private ShowMapper showMapper;

    @Resource
    private PostLikeMapper postLikeMapper;

    /**
     * @param currentUserId 游客传 null；登录用户用于计算 likedByMe
     */
    public List<PostVO> toVOList(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return List.of();
        }

        Set<Long> userIds = posts.stream()
                .map(Post::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, User> users = userIds.isEmpty()
                ? Map.of()
                : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))
                .stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        Set<Long> showIds = posts.stream()
                .map(Post::getShowId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Show> shows = showIds.isEmpty()
                ? Map.of()
                : showMapper.selectList(new LambdaQueryWrapper<Show>().in(Show::getId, showIds))
                .stream()
                .collect(Collectors.toMap(Show::getId, Function.identity()));

        Set<Long> likedPostIds = likedPostIds(posts, currentUserId);

        List<PostVO> result = new ArrayList<>(posts.size());
        for (Post post : posts) {
            result.add(toVO(post, users, shows, likedPostIds));
        }
        return result;
    }

    private Set<Long> likedPostIds(List<Post> posts, Long currentUserId) {
        if (currentUserId == null) {
            return Set.of();
        }
        List<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toList());
        return postLikeMapper.selectList(new LambdaQueryWrapper<PostLike>()
                        .eq(PostLike::getUserId, currentUserId)
                        .in(PostLike::getPostId, postIds))
                .stream()
                .map(PostLike::getPostId)
                .collect(Collectors.toSet());
    }

    private PostVO toVO(Post post, Map<Long, User> users, Map<Long, Show> shows,
                        Set<Long> likedPostIds) {
        PostVO vo = new PostVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());

        User author = users.get(post.getUserId());
        if (author != null) {
            vo.setNickname(author.getNickname());
            vo.setIcon(author.getIcon());
        }

        vo.setShowId(post.getShowId());
        Show show = post.getShowId() == null ? null : shows.get(post.getShowId());
        if (show != null) {
            vo.setShowName(show.getName());
            vo.setShowPoster(show.getPoster());
        }

        vo.setSessionId(post.getSessionId());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setImages(splitImages(post.getImages()));
        vo.setLiked(post.getLiked());
        vo.setComments(post.getComments());
        vo.setLikedByMe(likedPostIds.contains(post.getId()));
        vo.setCreateTime(post.getCreateTime());
        return vo;
    }

    private List<String> splitImages(String images) {
        if (images == null || images.isBlank()) {
            return List.of();
        }
        return Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .collect(Collectors.toList());
    }
}
