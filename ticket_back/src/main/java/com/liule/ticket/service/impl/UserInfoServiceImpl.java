package com.liule.ticket.service.impl;

import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.UserInfoMapper;
import com.liule.ticket.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
