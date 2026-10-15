package com.liule.ticket.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\heimadianping\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;

    /** 订单支付时限（分钟）。超时后订单失效，占用的 lock_stock 归还给可售 stock */
    public static final int ORDER_PAY_TIMEOUT_MINUTES = 15;
}
