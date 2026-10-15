package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.Show;

public interface IShowService extends IService<Show> {

    /**
     * 按演出类别筛选，城市为可选辅助条件。
     *
     * @param categoryId 类别ID，必填
     * @param city       城市名，可空
     * @param page       页码，从 1 开始，可空
     */
    Result listByCategory(Long categoryId, String city, Integer page);

    /**
     * 按场馆筛选该场馆下的演出。
     *
     * @param venueId 场馆ID，必填
     * @param page    页码，从 1 开始，可空
     */
    Result listByVenue(Long venueId, Integer page);

    Result queryDetailById(Long id);
}
