package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.UserAttendee;

/**
 * 用户常用观演人。
 * <p>
 * 只做基础 CRUD —— {@code uk_user_idcard(user_id, id_card)} 在数据库层挡住
 * "同一账号重复添加同一个人"，业务层再补一次前置查询给友好提示。
 */
public interface UserAttendeeMapper extends BaseMapper<UserAttendee> {
}
