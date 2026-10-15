package com.liule.ticket.controller;

import com.liule.ticket.service.IShowCategoryService;

import com.liule.ticket.dto.Result;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/show")
public class ShowCategoryController {
    @Resource
    private IShowCategoryService ShowCategoryService;
    @GetMapping("/category/list")
    public Result listShowCategory() {
        return  ShowCategoryService.listShowCategory();
    }

}
