package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.entity.UserPurchase;
import com.liule.ticket.mapper.UserPurchaseMapper;
import com.liule.ticket.service.IUserPurchaseService;
import org.springframework.stereotype.Service;

@Service
public class UserPurchaseServiceImpl extends ServiceImpl<UserPurchaseMapper, UserPurchase>
        implements IUserPurchaseService {

}
