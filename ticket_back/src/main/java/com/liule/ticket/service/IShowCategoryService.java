package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.ShowCategory;

public interface IShowCategoryService extends IService<ShowCategory> {

    Result listShowCategory();
}
