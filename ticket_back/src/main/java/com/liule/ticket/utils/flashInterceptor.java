package com.liule.ticket.utils;


import cn.hutool.core.bean.BeanUtil;
import com.liule.ticket.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.liule.ticket.utils.RedisConstants.LOGIN_USER_KEY;
import static com.liule.ticket.utils.RedisConstants.LOGIN_USER_TTL;

public class flashInterceptor implements HandlerInterceptor {
    private  StringRedisTemplate stringRedisTemplate;
    public flashInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取token
        String token =request.getHeader("authorization");
        if (token == null) {

            return true;
        }
        // 查询redis,取用戶
       Map<Object, Object> usermap = stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);

        if (usermap.isEmpty()) {
            response.setStatus(401);
            return true;
        }
        // 保存用户
        UserDTO user = BeanUtil.fillBeanWithMap(usermap, new UserDTO(), false);

        UserHolder.saveUser((UserDTO) user);
        // 刷新有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 放行
        return true;

    }



    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
