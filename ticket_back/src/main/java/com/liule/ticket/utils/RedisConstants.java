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
}
