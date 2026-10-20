package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.TicketVO;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.OrderAttendee;
import com.liule.ticket.entity.Show;
import com.liule.ticket.entity.ShowSession;
import com.liule.ticket.entity.Ticket;
import com.liule.ticket.entity.TicketOrder;
import com.liule.ticket.entity.TicketTier;
import com.liule.ticket.exception.BizException;
import com.liule.ticket.mapper.OrderAttendeeMapper;
import com.liule.ticket.mapper.TicketMapper;
import com.liule.ticket.service.IShowService;
import com.liule.ticket.service.IShowSessionService;
import com.liule.ticket.service.ITicketService;
import com.liule.ticket.service.ITicketTierService;
import com.liule.ticket.utils.RedisIdWorker;
import com.liule.ticket.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket>
        implements ITicketService {

    @Resource
    private OrderAttendeeMapper orderAttendeeMapper;

    @Resource
    private ITicketTierService ticketTierService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private IShowService showService;

    @Resource
    private IShowSessionService showSessionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void issueTickets(TicketOrder order) {
        // 幂等：已经出过票就直接返回。
        // 正常的重复支付会被 finishOrder 的 status=0 条件挡住，这里再兜一层。
        Long issuedForOrder = count(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getOrderId, order.getId()));
        if (issuedForOrder != null && issuedForOrder > 0) {
            log.info("订单 {} 已出票 {} 张，跳过", order.getId(), issuedForOrder);
            return;
        }

        // 一票一人：按 seq 顺序取观演人
        List<OrderAttendee> attendees = orderAttendeeMapper.selectList(
                new LambdaQueryWrapper<OrderAttendee>()
                        .eq(OrderAttendee::getOrderId, order.getId())
                        .orderByAsc(OrderAttendee::getSeq));
        if (attendees.size() != order.getQuantity()) {
            // finishOrder 已经校验过，这里是兜底：
            // 宁可整单失败，也不要出一道没有观演人的票
            throw new BizException("订单 " + order.getId() + " 的观演人数量与购票数量不一致");
        }

        TicketTier tier = ticketTierService.getById(order.getTierId());
        if (tier == null) {
            throw new BizException("票档不存在");
        }

        // 占位座位号：从"这个票档已经出了多少张"往后排。
        // 它不对应真实座位，只用于票根显示；并发下可能重号，票号(id)才是唯一标识。
        long issuedForTier = count(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTierId, order.getTierId()));

        List<Ticket> tickets = new ArrayList<>(attendees.size());
        for (int i = 0; i < attendees.size(); i++) {
            OrderAttendee attendee = attendees.get(i);

            // seat_type=0 对号入座 → 填占位号；seat_type=1 站票/通票 → 留空
            String seatNo = "";
            if (Integer.valueOf(0).equals(tier.getSeatType())) {
                seatNo = tier.getSeatArea() + "-" + (issuedForTier + i + 1);
            }

            tickets.add(new Ticket()
                    .setId(redisIdWorker.create_ID("ticket"))
                    .setOrderId(order.getId())
                    .setUserId(order.getUserId())
                    .setShowId(order.getShowId())
                    .setSessionId(order.getSessionId())
                    .setTierId(order.getTierId())
                    .setSeatNo(seatNo)
                    .setRealName(attendee.getRealName())
                    .setIdCard(attendee.getIdCard())
                    // 出票时先给一个初始二维码；之后每次 GET /ticket/{ticketId}/qr 都会换新值
                    .setQrCode(UUID.randomUUID().toString().replace("-", ""))
                    .setStatus(0));
        }

        saveBatch(tickets);
        log.info("订单 {} 出票 {} 张", order.getId(), tickets.size());
    }

    @Override
    public Result listMine() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        List<Ticket> tickets = list(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getUserId, user.getId())
                .orderByDesc(Ticket::getCreateTime)
                .orderByDesc(Ticket::getId));

        return Result.ok(toTicketVOList(tickets));
    }

    @Override
    public Result refreshQr(Long ticketId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        Ticket ticket = getById(ticketId);
        // 查不到和不是自己的都返回同一句话，避免泄露"这个票号存在"
        if (ticket == null || !user.getId().equals(ticket.getUserId())) {
            return Result.fail("票不存在");
        }
        if (!Integer.valueOf(0).equals(ticket.getStatus())) {
            return Result.fail("该票已核销或已退票，无法刷新二维码");
        }

        // 刷新即作废旧码：每次生成全新的 UUID 写回，旧截图随即失效
        String qrCode = UUID.randomUUID().toString().replace("-", "");
        boolean updated = update()
                .set("qr_code", qrCode)
                .eq("id", ticketId)
                .eq("user_id", user.getId())
                .eq("status", 0)
                .update();
        if (!updated) {
            // 并发下票刚被核销/退票，条件更新会失败
            return Result.fail("该票状态已变化，请刷新票夹");
        }

        ticket.setQrCode(qrCode);
        return Result.ok(toTicketVOList(List.of(ticket)).get(0));
    }

    /**
     * 批量装配票夹展示字段：一次查齐演出、场次、票档，避免逐票回源。
     */
    private List<TicketVO> toTicketVOList(List<Ticket> tickets) {
        if (tickets == null || tickets.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> showIds = tickets.stream()
                .map(Ticket::getShowId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> sessionIds = tickets.stream()
                .map(Ticket::getSessionId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        List<Long> tierIds = tickets.stream()
                .map(Ticket::getTierId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Show> showMap = showIds.isEmpty() ? new HashMap<>()
                : showService.listByIds(showIds).stream()
                        .collect(Collectors.toMap(Show::getId, Function.identity()));
        Map<Long, ShowSession> sessionMap = sessionIds.isEmpty() ? new HashMap<>()
                : showSessionService.listByIds(sessionIds).stream()
                        .collect(Collectors.toMap(ShowSession::getId, Function.identity()));
        Map<Long, TicketTier> tierMap = tierIds.isEmpty() ? new HashMap<>()
                : ticketTierService.listByIds(tierIds).stream()
                        .collect(Collectors.toMap(TicketTier::getId, Function.identity()));

        List<TicketVO> result = new ArrayList<>(tickets.size());
        for (Ticket ticket : tickets) {
            TicketVO vo = new TicketVO();
            vo.setTicket(ticket);

            Show show = showMap.get(ticket.getShowId());
            if (show != null) {
                vo.setShowName(show.getName());
                vo.setPoster(show.getPoster());
            }

            ShowSession session = sessionMap.get(ticket.getSessionId());
            if (session != null) {
                vo.setSessionName(session.getSessionName());
                vo.setSessionTime(session.getSessionTime());
            }

            TicketTier tier = tierMap.get(ticket.getTierId());
            if (tier != null) {
                vo.setTierName(tier.getName());
                vo.setSeatArea(tier.getSeatArea());
            }

            result.add(vo);
        }
        return result;
    }
}
