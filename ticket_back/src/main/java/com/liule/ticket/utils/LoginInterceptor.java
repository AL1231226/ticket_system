package com.liule.ticket.utils;


import cn.hutool.core.bean.BeanUtil;
import com.liule.ticket.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.liule.ticket.utils.RedisConstants.LOGIN_USER_KEY;
import static com.liule.ticket.utils.RedisConstants.LOGIN_USER_TTL;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
         UserDTO user = UserHolder.getUser() ;
         if (user == null) {
             response.setStatus(401);
             return false;
         }
        // 放行
        return true;

    }



    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
