package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.OrderAttendee;

/**
 * 订单观演人（一票一人）。
 * <p>
 * "一证一票"由数据库的
 * {@code uk_session_idcard(session_id, id_card)} 兜底 ——
 * 插入冲突会抛 {@code DuplicateKeyException}，调用方要转成友好提示。
 */
public interface OrderAttendeeMapper extends BaseMapper<OrderAttendee> {
}
