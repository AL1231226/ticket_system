package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.LoginFormDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.User;

public interface IUserService extends IService<User> {

    Result sendcode(String phone);

    Result login(LoginFormDTO loginForm);

    Result logout(String token);

    Result sign();

    Result signCount();

    /** 公开个人主页：资料 + 粉丝/关注/动态数 + isFollowing */
    Result queryPublicProfile(Long userId);
}
