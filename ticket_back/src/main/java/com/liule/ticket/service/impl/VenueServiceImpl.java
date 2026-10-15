package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.entity.Venue;
import com.liule.ticket.mapper.VenueMapper;
import com.liule.ticket.service.IVenueService;
import org.springframework.stereotype.Service;

@Service
public class VenueServiceImpl extends ServiceImpl<VenueMapper, Venue> implements IVenueService {

}
