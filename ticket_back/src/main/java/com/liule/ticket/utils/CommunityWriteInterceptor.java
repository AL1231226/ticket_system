package com.liule.ticket.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 社区写操作鉴权。
 * <p>
 * 社区路径（/post、/comment、/follow）已从 LoginInterceptor 的白名单排除，
 * 以便 GET 公开；这个拦截器把非 GET 方法补回来：
 * <ul>
 *   <li>GET / HEAD / OPTIONS：公开读，放行</li>
 *   <li>POST / PUT / DELETE：必须已登录，否则 401</li>
 * </ul>
 * flashInterceptor 先执行（order 0），所以这里能读到 UserHolder。
 */
public class CommunityWriteInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
