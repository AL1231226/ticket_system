package com.liule.ticket.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOW_TTL = 20L;
    public static final String CACHE_SHOW_KEY = "cache:show:";

    /** 演出不存在时的空值缓存。防穿透：查不到的 id 也写一个短 TTL 的空串，避免每次都打数据库 */
    public static final String CACHE_NULL_SHOW_KEY = "cache:null:show:";


    public static final String LOCK_SHOW_KEY = "lock:show:";
    public static final Long LOCK_SHOW_TTL = 10L;

//某票档的库存
    public static final String SECKILL_STOCK_KEY = "ticker:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    //限购计数：Hash，field = userId，value = 该用户在本场次已购数量
    public static final String SECKILL_ORDERED_KEY = "ticker:ordered:";

    /**
     * 秒杀落库失败的标记，值为失败原因。
     * <p>
     * 异步下单里，订单先是"不存在"，落库失败后也仍然"不存在" —— 轮询接口
     * 光看订单表区分不出"还在生成中"和"已经失败了"，用户只能干等到超时。
     * 这个 key 让失败变成一个可查询的终态。
     * <p>
     * TTL 给短的：它只服务于抢完票后那几十秒的轮询，不是长期记录。
     */
    public static final String SECKILL_FAILED_KEY = "ticker:failed:";
    public static final Long SECKILL_FAILED_TTL = 5L;

    /** 下单消息流 */
    public static final String ORDER_STREAM_KEY = "stream.orders";
    public static final String ORDER_STREAM_GROUP = "g1";
    public static final String ORDER_STREAM_CONSUMER = "c1";

    // ==================== Plan 3：回流 / 候补 / 风控 ====================

    /** 支付超时延迟队列：member=orderId，score=到期时间戳(ms) */
    public static final String DELAY_ORDER_PAY_KEY = "ticker:delay:order:pay";

    /** 回流分配锁：票档级，保护「查候补 → 分配/回池」临界区 */
    public static final String REFLOW_LOCK_KEY = "ticker:lock:reflow:";

    /** 候补队列：member=userId，score=入队时间戳(ms) */
    public static final String WAITLIST_QUEUE_KEY = "ticker:waitlist:queue:";

    /** 用户级滑动窗口限流 */
    public static final String LIMIT_USER_KEY = "ticker:limit:user:";

    /** IP 级滑动窗口限流 */
    public static final String LIMIT_IP_KEY = "ticker:limit:ip:";

    /** 设备-账号集合，用于一设备多账号检测 */
    public static final String DEVICE_USERS_KEY = "ticker:device:users:";

    // ==================== Plan 4：社区 Feed ====================

    /** 全站最新 Feed：ZSet，member=postId，score=发布时间(ms) */
    public static final String FEED_GLOBAL_KEY = "ticker:feed:global";

    /** 个人 Feed：ZSet，完整 key 为 ticker:feed:{userId} */
    public static final String FEED_USER_KEY = "ticker:feed:";

    /** 用户关注集合：Set，完整 key 为 ticker:follow:{userId} */
    public static final String FOLLOW_USER_KEY = "ticker:follow:";

    /** 动态点赞镜像：ZSet，member=userId，score=点赞时间(ms)，完整 key 为 ticker:post:liked:{postId} */
    public static final String POST_LIKED_KEY = "ticker:post:liked:";
}
