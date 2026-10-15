package com.liule.ticket.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.ShowCategory;
import com.liule.ticket.mapper.ShowCategoryMapper;
import com.liule.ticket.service.IShowCategoryService;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShowCategoryServiceImpl extends ServiceImpl<ShowCategoryMapper, ShowCategory>
        implements IShowCategoryService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result listShowCategory() {
        String key="cache:show:type";
        String cacheValue = stringRedisTemplate.opsForValue().get(key);
        if(cacheValue != null){
            List<ShowCategory> showCategoryList = JSONUtil.toList(cacheValue, ShowCategory.class);
            return Result.ok(showCategoryList, (long) showCategoryList.size());
        }
        QueryWrapper<ShowCategory> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc("sort");
        List<ShowCategory> categories = list(queryWrapper);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(categories != null ? categories : java.util.Collections.emptyList()));
        return Result.ok(categories, (long) categories.size());
    }
}
