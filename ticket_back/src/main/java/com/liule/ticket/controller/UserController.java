package com.liule.ticket.controller;


import cn.hutool.core.bean.BeanUtil;
import com.liule.ticket.dto.LoginFormDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.service.IUserInfoService;
import com.liule.ticket.service.IUserService;
import com.liule.ticket.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     */
    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendcode(phone);
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm){
        return userService.login(loginForm);
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        // 获取token并删除Redis中的登录信息，实现登出
        String token = request.getHeader("authorization");
        UserHolder.removeUser();
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO  user = UserHolder.getUser() ;
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /** 公开个人主页：游客可读，登录时带 isFollowing */
    @GetMapping("/profile/{id}")
    public Result profile(@PathVariable("id") Long userId){
        return userService.queryPublicProfile(userId);
    }

    @GetMapping("/{id}")
    public Result getUser(@PathVariable("id") Long id){
        // 查询用户
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回用户
        return Result.ok(userDTO);
    }

    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

}
