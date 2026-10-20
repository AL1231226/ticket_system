package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.AttendeeFormDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.UserAttendee;

/**
 * 我的常用观演人。
 */
public interface IUserAttendeeService extends IService<UserAttendee> {

    /**
     * 新增观演人。
     * <p>
     * 同一账号不能重复添加同一个人（{@code uk_user_idcard} 兜底）。
     * 演示项目不做真实实名认证，新增即视为已认证。
     */
    Result add(AttendeeFormDTO form);

    /**
     * 当前用户的观演人列表（证件号脱敏）。
     */
    Result listMine();

    /**
     * 删除观演人。
     * <p>
     * 被进行中（待支付）或已完成（已支付）的订单引用时拒绝删除 ——
     * 那些订单需要这份实名记录。
     */
    Result delete(Long id);

}
