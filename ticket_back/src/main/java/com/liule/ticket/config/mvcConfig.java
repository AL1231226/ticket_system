package com.liule.ticket.config;

import com.liule.ticket.utils.CommunityWriteInterceptor;
import com.liule.ticket.utils.LoginInterceptor;
import com.liule.ticket.utils.SystemConstants;
import com.liule.ticket.utils.flashInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;
import java.io.File;

@Configuration
public class  mvcConfig  implements WebMvcConfigurer {
    @Resource
    private  StringRedisTemplate stringRedisTemplate;


    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        // 错误转发不应再走登录校验，否则匿名访问不存在的公开路径会被包成 401
                        "/error",
                        "/show/**",
                        "/venue/**",
                        "/ticket-tier/**",
                        "/uploads/**",
                        // 社区读公开：这些路径的 GET 放行，非 GET 由下面的拦截器补登录校验
                        "/post/**",
                        "/comment/**",
                        "/follow/**",
                        "/user/profile/**"
                ).order(1);
        registry.addInterceptor(new CommunityWriteInterceptor())
                .addPathPatterns("/post/**", "/comment/**", "/follow/**")
                .order(1);
        registry.addInterceptor(new flashInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = new File(SystemConstants.IMAGE_UPLOAD_DIR).toURI().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }
}
