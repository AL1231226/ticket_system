package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.Waitlist;

/**
 * 候补队列。
 * <p>
 * 只做基础 CRUD；排队顺序在 Redis ZSet，补位订单关联靠 {@code order_id}。
 */
public interface WaitlistMapper extends BaseMapper<Waitlist> {
}
