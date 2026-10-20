package com.liule.ticket.controller;

import com.liule.ticket.dto.AttendeeFormDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.service.IUserAttendeeService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的常用观演人。
 * <p>
 * 需要登录 —— 这个路径不在拦截器白名单里，走 {@code authorization} 头。
 */
@RestController
@RequestMapping("/user/attendee")
public class UserAttendeeController {

    @Resource
    private IUserAttendeeService userAttendeeService;

    /**
     * 新增观演人。
     * <pre>
     * POST /api/user/attendee
     * {"realName":"张三","idCard":"110101199001011234","idCardType":1}
     * </pre>
     */
    @PostMapping("/add")
    public Result add(@RequestBody AttendeeFormDTO form) {
        return userAttendeeService.add(form);
    }

    /**
     * 我的观演人列表（证件号脱敏）。
     */
    @GetMapping("/list")
    public Result list() {
        return userAttendeeService.listMine();
    }

    /**
     * 删除观演人。被有效订单引用时会拒绝。
     */
    @DeleteMapping("/delete/{id}")
    public Result delete(@PathVariable("id") Long id) {
        return userAttendeeService.delete(id);
    }

}
