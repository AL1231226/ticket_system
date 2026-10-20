package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户常用观演人（账号级实名信息库）。
 * <p>
 * 用户开票前就在这里把观演人录好并完成认证；下单时是从这里<b>勾选</b>，
 * 而不是在秒杀请求里现填 —— 实名信息不该出现在抢票热路径上。
 * <p>
 * {@code (user_id, id_card)} 唯一：同一账号不能把同一个人加两次。
 * 如果要执行"一个证件只能绑一个账号"的平台策略，把 {@code idx_idcard}
 * 升级成唯一索引即可。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_attendee")
public class UserAttendee implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 归属账号
     */
    private Long userId;

    /**
     * 观演人姓名
     */
    private String realName;

    /**
     * 证件号。
     * <p>
     * 真实项目里这属于敏感个人信息，应该加密存储 + 查询脱敏；
     * 这里按明文演示。
     */
    private String idCard;

    /**
     * 证件类型：1 身份证 / 2 港澳居民来往内地通行证 /
     * 3 台湾居民来往大陆通行证 / 4 护照
     */
    private Integer idCardType;

    /**
     * 实名认证状态：0 未认证 / 1 已认证 / 2 认证失败。
     * 强实名场次只有 1 能下单。
     */
    private Integer verifyStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

}
