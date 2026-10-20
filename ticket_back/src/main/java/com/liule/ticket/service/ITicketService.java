package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.Ticket;
import com.liule.ticket.entity.TicketOrder;

public interface ITicketService extends IService<Ticket> {

    /**
     * 按订单出票：一票一人，从 {@code tb_order_attendee} 逐张复制到 {@code tb_ticket}。
     * <p>
     * 幂等：同一个订单已经出过票就直接返回，重复支付不会重复出票。
     * <p>
     * 座位号是<b>占位号</b>（{@code 区域-序号}），不是真实选座结果；
     * 站票/通票不生成座位号。票号才是唯一标识。
     *
     * @throws com.liule.ticket.exception.BizException 观演人数量与购票数量不一致 / 票档不存在
     */
    void issueTickets(TicketOrder order);

    /**
     * 我的票夹：当前用户的所有票，按出票时间倒序。
     * <p>
     * 每张票带演出名、海报、场次名/时间、票档名、座位区，
     * 前端不用再为每张票逐条查演出信息。
     */
    Result listMine();

    /**
     * 刷新动态二维码：校验票属于当前用户且未使用，生成新 UUID 写回 {@code qr_code}。
     * <p>
     * 每次刷新都换新值，旧截图随即作废。接口本身不做频率限制，
     * 前端约定每 60 秒刷新一次。
     */
    Result refreshQr(Long ticketId);

}
