package com.liule.ticket.controller;

import com.liule.ticket.dto.CommentCreateDTO;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.Result;
import com.liule.ticket.service.ICommentService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态评论接口（v1 只有一级评论）。
 * <p>
 * GET 公开；POST/DELETE 由 CommunityWriteInterceptor 校验登录后放行。
 */
@RestController
@RequestMapping("/comment")
public class CommentController {

    @Resource
    private ICommentService commentService;

    @PostMapping
    public Result publish(@RequestBody CommentCreateDTO dto) {
        return commentService.publish(dto);
    }

    /**
     * 某动态的评论，时间倒序。
     * <pre>
     * GET /api/comment/of/post/{postId}?lastId=&amp;offset=0&amp;count=10
     * </pre>
     */
    @GetMapping("/of/post/{postId}")
    public Result ofPost(@PathVariable("postId") Long postId,
                         @RequestParam(value = "lastId", required = false) Long lastId,
                         @RequestParam(value = "offset", defaultValue = "0") Integer offset,
                         @RequestParam(value = "count", defaultValue = "10") Integer count) {
        FeedCursor cursor = lastId == null ? FeedCursor.empty() : FeedCursor.of(lastId, offset);
        return commentService.ofPost(postId, cursor, count);
    }

    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return commentService.delete(id);
    }
}
