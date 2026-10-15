package com.liule.ticket.controller;

import com.liule.ticket.dto.Result;
import com.liule.ticket.service.ITicketTierService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ticket-tier")
public class TicketTierController {
    @Resource
    private ITicketTierService ticketTierService;

    /**
     * 票档余量。详情页开票倒计时轮询用，每 5 秒一次。
     * 例：/ticket-tier/1
     */
    @GetMapping("/{tierId}")
    public Result stock(@PathVariable("tierId") Long tierId) {
        Long stock = ticketTierService.queryStock(tierId);
        if (stock == null) {
            return Result.fail("票档不存在");
        }
        return Result.ok(stock);
    }

    /**
     * 把所有上架票档的余量预热进 Redis。开票前手工调一次。
     * 幂等，重复调不会覆盖已经扣减的库存。
     */
    @PostMapping("/preload")
    public Result preload() {
        return Result.ok(ticketTierService.preloadStock());
    }

}
