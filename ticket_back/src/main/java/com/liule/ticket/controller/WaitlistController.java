package com.liule.ticket.controller;

import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.WaitlistJoinDTO;
import com.liule.ticket.service.IWaitlistService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候补队列。
 * <p>
 * 这个路径不在拦截器白名单里，需要登录 —— 走 {@code authorization} 头。
 */
@RestController
@RequestMapping("/waitlist")
public class WaitlistController {

    @Resource
    private IWaitlistService waitlistService;

    /**
     * 加入候补，排到队尾。
     * <pre>
     * POST /api/waitlist/join
     * {"tierId":2,"quantity":1}
     * </pre>
     * 重复加入、已获得补位、已购票都会被拒绝；过期/取消后可重新加入，排到队尾。
     */
    @PostMapping("/join")
    public Result join(@RequestBody WaitlistJoinDTO form) {
        return waitlistService.join(form);
    }

    /**
     * 我的候补列表，按入队时间倒序。
     * <pre>
     * GET /api/waitlist/my
     * </pre>
     * 排队中的行带实时 position（从 1 开始）和队列总人数 total。
     */
    @GetMapping("/my")
    public Result my() {
        return waitlistService.listMine();
    }

    /**
     * 我在指定票档的排队位置。
     * <pre>
     * GET /api/waitlist/{tierId}/position
     * </pre>
     */
    @GetMapping("/{tierId}/position")
    public Result position(@PathVariable("tierId") Long tierId) {
        return waitlistService.position(tierId);
    }

    /**
     * 退出候补：状态置 4 已取消，并从队列移除。
     * <pre>
     * DELETE /api/waitlist/{tierId}
     * </pre>
     * 已获得补位（status=1）时不允许直接退出，要先取消补位订单。
     */
    @DeleteMapping("/{tierId}")
    public Result quit(@PathVariable("tierId") Long tierId) {
        return waitlistService.quit(tierId);
    }
}
