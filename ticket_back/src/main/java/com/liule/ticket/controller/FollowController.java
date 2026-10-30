package com.liule.ticket.controller;

import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.Result;
import com.liule.ticket.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注 / 粉丝接口。
 * <p>
 * GET 公开；POST 由 CommunityWriteInterceptor 校验登录后放行。
 * GET /follow/common/{userId} 虽然公开可路由，但 Service 内强制登录。
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /** true 关注 / false 取关，幂等 */
    @PostMapping("/{userId}/{isFollow}")
    public Result toggle(@PathVariable("userId") Long userId,
                         @PathVariable("isFollow") boolean isFollow) {
        return followService.toggle(userId, isFollow);
    }

    /**
     * 关注 / 粉丝列表。
     * <pre>
     * GET /api/follow/list/{userId}?type=followees|fans&amp;lastId=&amp;count=10
     * </pre>
     */
    @GetMapping("/list/{userId}")
    public Result list(@PathVariable("userId") Long userId,
                       @RequestParam(value = "type", defaultValue = "followees") String type,
                       @RequestParam(value = "lastId", required = false) Long lastId,
                       @RequestParam(value = "count", defaultValue = "10") Integer count) {
        FeedCursor cursor = lastId == null ? FeedCursor.empty() : FeedCursor.of(lastId, 0);
        return followService.list(userId, type, cursor, count);
    }

    /** 我和目标用户的共同关注，最多 50 个 */
    @GetMapping("/common/{userId}")
    public Result common(@PathVariable("userId") Long userId) {
        return followService.common(userId);
    }
}
