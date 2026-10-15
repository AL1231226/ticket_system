package com.liule.ticket.controller;

import com.liule.ticket.dto.Result;
import com.liule.ticket.service.IShowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/show")
public class ShowController {

    @Resource
    private IShowService showService;

    /**
     * 演出列表：按类别筛选，城市为可选辅助条件，在者为演出开始时间
     * 例：/show/list/byCategory?categoryId=1&city=上海&page=1
     */
    @GetMapping("/list/byCategory")
    public Result listByCategory(@RequestParam Long categoryId,
                                 @RequestParam(required = false) String city,
                                 @RequestParam(required = false, defaultValue = "1") Integer page) {
        return showService.listByCategory(categoryId, city, page);
    }

    /**
     * 演出列表：按场馆筛选，再者为开始时间
     * 例：/show/list/byVenue?venueId=1&page=1
     */
    @GetMapping("/list/byVenue")
    public Result listByVenue(@RequestParam Long venueId,
                              @RequestParam(required = false, defaultValue = "1") Integer page) {
        return showService.listByVenue(venueId, page);
    }

    /** 演出详情 */
    @GetMapping("/{id}")
    public Result detail(@PathVariable("id") Long id) {
        return showService.queryDetailById(id);
    }

}
