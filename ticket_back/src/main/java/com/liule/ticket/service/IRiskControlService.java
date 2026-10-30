package com.liule.ticket.service;

/**
 * 抢票入口风控。
 * <p>
 * 账号层限购已经在 Lua 抢票脚本里做掉；这里补的是频率层和设备层：
 * <ul>
 *   <li>用户级滑动窗口：默认 5 次/秒；</li>
 *   <li>IP 级滑动窗口：默认 50 次/秒，避免误伤本地多用户 e2e；</li>
 *   <li>设备-账号集合：超过 3 个账号只记风险日志，不拦截。</li>
 * </ul>
 */
public interface IRiskControlService {

    /**
     * 检查一次抢票请求是否放行。
     *
     * @param userId   当前登录用户
     * @param ip       客户端 IP；为空时跳过 IP 层
     * @param deviceId 设备指纹（请求头 X-Device-Id）；为空时跳过设备层
     * @return true=放行；false=频率超限
     */
    boolean check(Long userId, String ip, String deviceId);
}
