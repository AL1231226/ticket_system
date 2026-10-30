package com.liule.ticket.utils;

public class SystemConstants {
    /** 社区图片上传目录。只映射这个子目录，避免把 D:\ticket_photos 下的其他文件暴露出去 */
    public static final String IMAGE_UPLOAD_DIR = "D:\\ticket_photos\\uploads\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    /** 订单支付时限（分钟）。超时后订单失效，占用的 lock_stock 归还给可售 stock */
    public static final int ORDER_PAY_TIMEOUT_MINUTES = 15;

    /** 候补补位支付时限（分钟）。比普通订单短，避免回流票被长时间占住 */
    public static final int WAITLIST_PAY_TIMEOUT_MINUTES = 10;

    // ==================== E11-8 抢票风控 ====================

    /** 滑动窗口大小（毫秒） */
    public static final long RISK_WINDOW_MILLIS = 1000L;

    /** 单用户每秒抢票次数上限 */
    public static final int RISK_USER_LIMIT = 5;

    /** 单 IP 每秒抢票次数上限。放宽到 50，避免误伤本地多用户 e2e */
    public static final int RISK_IP_LIMIT = 50;

    /** 一个设备关联超过多少个账号时记风险日志（只记录，不拦截） */
    public static final int RISK_DEVICE_ACCOUNT_LIMIT = 3;

    /** 设备-账号集合 TTL（秒）。不设 TTL 的话集合会永久膨胀 */
    public static final long RISK_DEVICE_TTL_SECONDS = 7 * 24 * 3600L;

    // ==================== Plan 4：社区 Feed ====================

    /** 全站 Feed ZSet 上限 */
    public static final int FEED_GLOBAL_MAX = 5000;

    /** 个人 Feed ZSet 上限 */
    public static final int FEED_USER_MAX = 1000;

    /** 个人 Feed key 丢失时，从 DB 重建的条数 */
    public static final int FEED_REBUILD_SIZE = 200;

    /** 关注成功时回填对方最近动态的条数 */
    public static final int FEED_FOLLOW_BACKFILL_SIZE = 20;

    /** 取关清理时最多扫描个人 Feed 的条数 */
    public static final int FEED_UNFOLLOW_SCAN_SIZE = 1000;

    /** 点赞 Top5 默认条数 */
    public static final int POST_LIKED_TOP_N = 5;

    /** 点赞用户列表单次最多返回条数 */
    public static final int POST_LIKED_TOP_MAX = 20;

    /** 点赞 ZSet 镜像上限 */
    public static final int POST_LIKED_MAX = 1000;

    /** 关注 / 粉丝列表单页最多条数 */
    public static final int FOLLOW_LIST_MAX = 50;

    /** 共同关注最多返回条数（v1 不分页） */
    public static final int FOLLOW_COMMON_MAX = 50;
}
