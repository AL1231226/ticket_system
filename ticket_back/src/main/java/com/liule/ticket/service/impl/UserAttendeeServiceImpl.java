package com.liule.ticket.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.AttendeeFormDTO;
import com.liule.ticket.dto.AttendeeVO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.OrderAttendee;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.entity.UserAttendee;
import com.liule.ticket.mapper.OrderAttendeeMapper;
import com.liule.ticket.mapper.TicketOrderMapper;
import com.liule.ticket.mapper.UserAttendeeMapper;
import com.liule.ticket.service.IUserAttendeeService;
import com.liule.ticket.utils.RegexUtils;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserAttendeeServiceImpl extends ServiceImpl<UserAttendeeMapper, UserAttendee>
        implements IUserAttendeeService {

    /** 证件类型：身份证 */
    private static final int ID_CARD_TYPE_IDENTITY = 1;

    @Resource
    private OrderAttendeeMapper orderAttendeeMapper;

    @Resource
    private TicketOrderMapper ticketOrderMapper;

    @Override
    public Result add(AttendeeFormDTO form) {
        Long userId = UserHolder.getUser().getId();

        if (form == null
                || StrUtil.isBlank(form.getRealName())
                || StrUtil.isBlank(form.getIdCard())) {
            return Result.fail("姓名和证件号不能为空");
        }

        String realName = form.getRealName().trim();
        int cardType = form.getIdCardType() == null
                ? ID_CARD_TYPE_IDENTITY : form.getIdCardType();
        // 统一大写：身份证末位的 X 不能因为大小写被当成两个人
        String idCard = form.getIdCard().trim().toUpperCase();

        if (realName.length() > 32) {
            return Result.fail("姓名过长");
        }
        if (cardType == ID_CARD_TYPE_IDENTITY && RegexUtils.isIdCardInvalid(idCard)) {
            return Result.fail("身份证号格式错误");
        }

        // 前置查一次，给用户友好提示；真正的唯一性由 uk_user_idcard 兜底
        boolean exists = count(new LambdaQueryWrapper<UserAttendee>()
                .eq(UserAttendee::getUserId, userId)
                .eq(UserAttendee::getIdCard, idCard)) > 0;
        if (exists) {
            return Result.fail("该观演人已存在");
        }

        UserAttendee attendee = new UserAttendee()
                .setUserId(userId)
                .setRealName(realName)
                .setIdCard(idCard)
                .setIdCardType(cardType)
                // 演示项目不做真实实名认证；生产环境这里应该是 0，
                // 等外部认证服务的回调再置为 1
                .setVerifyStatus(1);

        try {
            save(attendee);
        } catch (DuplicateKeyException e) {
            // 并发下前置查询可能都没查到，最终靠唯一索引挡住
            return Result.fail("该观演人已存在");
        }
        return Result.ok(attendee.getId());
    }

    @Override
    public Result listMine() {
        Long userId = UserHolder.getUser().getId();

        List<AttendeeVO> list = list(new LambdaQueryWrapper<UserAttendee>()
                .eq(UserAttendee::getUserId, userId)
                .orderByDesc(UserAttendee::getId))
                .stream()
                .map(a -> new AttendeeVO(
                        a.getId(),
                        a.getRealName(),
                        maskIdCard(a.getIdCard()),
                        a.getIdCardType(),
                        a.getVerifyStatus()))
                .collect(Collectors.toList());

        return Result.ok(list);
    }

    @Override
    public Result delete(Long id) {
        Long userId = UserHolder.getUser().getId();
        UserAttendee attendee = getById(id);
        if (attendee == null || !userId.equals(attendee.getUserId())) {
            return Result.fail("观演人不存在");
        }

        // 被"有效订单"引用时不能删：
        //   status=0 待支付 —— 还占着票，删除会让订单的归属说不清
        //   status=1 已支付 —— 交易留档，订单观演人还要能追溯到人
        // 已取消(2) / 已退款(3) / 已失效(4) 不受影响，可以删。
        List<OrderAttendee> refs = orderAttendeeMapper.selectList(
                new LambdaQueryWrapper<OrderAttendee>()
                        .select(OrderAttendee::getOrderId)
                        .eq(OrderAttendee::getAttendeeId, id));
        if (!refs.isEmpty()) {
            Set<Long> orderIds = refs.stream()
                    .map(OrderAttendee::getOrderId)
                    .collect(Collectors.toSet());
            Long active = ticketOrderMapper.selectCount(
                    new LambdaQueryWrapper<TicketOrder>()
                            .in(TicketOrder::getId, orderIds)
                            .in(TicketOrder::getStatus, 0, 1));
            if (active != null && active > 0) {
                return Result.fail("该观演人有进行中或已完成的订单，暂不能删除");
            }
        }

        removeById(id);
        return Result.ok();
    }

    /**
     * 证件号脱敏：保留前 6 位和后 4 位。
     * 18 位身份证 → {@code 110101********1234}
     */
    private static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6)
                + "********"
                + idCard.substring(idCard.length() - 4);
    }
}
