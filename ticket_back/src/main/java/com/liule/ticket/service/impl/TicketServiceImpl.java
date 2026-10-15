package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.entity.Ticket;
import com.liule.ticket.mapper.TicketMapper;
import com.liule.ticket.service.ITicketService;
import org.springframework.stereotype.Service;

@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket>
        implements ITicketService {

}
