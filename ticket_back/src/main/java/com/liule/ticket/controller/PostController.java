package com.liule.ticket.controller;

import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.PostCreateDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.service.IPostService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 社区动态接口空壳。
 * <p>
 * 路由按设计文档 7.5 先固定；GET 公开，POST/PUT/DELETE 由
 * CommunityWriteInterceptor 在校验登录后放行。
 */
@RestController
@RequestMapping("/post")
public class PostController {

    @Resource
    private IPostService postService;


    @PostMapping
    public Result publish(@RequestBody PostCreateDTO dto) {
        return postService.publish(dto);
    }

    /**
     * 社区流。游客或 mode=latest 走全站，登录且 mode=follow 走关注流。
     * <pre>
     * GET /api/post/scroll?lastId=&offset=0&count=10&mode=follow|latest
     * </pre>
     */
    @GetMapping("/scroll")
    public Result scroll(@RequestParam(value = "lastId", required = false) Long lastId,
                         @RequestParam(value = "offset", defaultValue = "0") Integer offset,
                         @RequestParam(value = "count", defaultValue = "10") Integer count,
                         @RequestParam(value = "mode", defaultValue = "latest") String mode) {
        return postService.scroll(mode, cursorOf(lastId, offset), count);
    }

    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") Long id) {
        return postService.detail(id);
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return postService.delete(id);
    }

    /** 点赞切换：未点赞则点赞，已点赞则取消。 */
    @PutMapping("/{id}/like")
    public Result like(@PathVariable("id") Long id) {
        return postService.toggleLike(id);
    }

    /** 最近点赞用户 Top5，公开接口。 */
    @GetMapping("/{id}/likes")
    public Result likedUsers(@PathVariable("id") Long id,
                             @RequestParam(value = "limit", defaultValue = "5") Integer limit) {
        return postService.likedUsers(id, limit);
    }

    /** 某用户的动态，游标分页。 */
    @GetMapping("/of/user/{userId}")
    public Result ofUser(@PathVariable("userId") Long userId,
                         @RequestParam(value = "lastId", required = false) Long lastId,
                         @RequestParam(value = "offset", defaultValue = "0") Integer offset,
                         @RequestParam(value = "count", defaultValue = "10") Integer count) {
        return postService.ofUser(userId, cursorOf(lastId, offset), count);
    }

    /** 某演出的动态，游标分页。 */
    @GetMapping("/of/show/{showId}")
    public Result ofShow(@PathVariable("showId") Long showId,
                         @RequestParam(value = "lastId", required = false) Long lastId,
                         @RequestParam(value = "offset", defaultValue = "0") Integer offset,
                         @RequestParam(value = "count", defaultValue = "10") Integer count) {
        return postService.ofShow(showId, cursorOf(lastId, offset), count);
    }

    private FeedCursor cursorOf(Long lastId, Integer offset) {
        return lastId == null ? FeedCursor.empty() : FeedCursor.of(lastId, offset);
    }
}
