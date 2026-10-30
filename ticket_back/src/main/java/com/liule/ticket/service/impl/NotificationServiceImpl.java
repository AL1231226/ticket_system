package com.liule.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.entity.Notification;
import com.liule.ticket.mapper.NotificationMapper;
import com.liule.ticket.service.INotificationService;
import com.liule.ticket.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification>
        implements INotificationService {

    @Override
    public Result listMine(Integer isRead) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        List<Notification> rows = list(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.getId())
                .eq(isRead != null, Notification::getIsRead, isRead)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId));
        return Result.ok(rows);
    }

    @Override
    public Result unreadCount() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        Long unread = count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, user.getId())
                .eq(Notification::getIsRead, 0));
        Map<String, Object> data = new HashMap<>();
        data.put("unread", unread == null ? 0 : unread);
        return Result.ok(data);
    }

    @Override
    public Result markRead(Long id) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        if (id == null) {
            return Result.fail("消息不存在");
        }

        Notification notification = getById(id);
        // 查不到和不是自己的都返回同一句话，避免泄露"这条消息存在"
        if (notification == null || !user.getId().equals(notification.getUserId())) {
            return Result.fail("消息不存在");
        }
        // 幂等：已读再点一次也成功
        if (Integer.valueOf(1).equals(notification.getIsRead())) {
            return Result.ok();
        }

        // 条件更新兜住并发：两个请求同时标已读时，后面的影响 0 行也算成功
        update().set("is_read", 1)
                .eq("id", id)
                .eq("user_id", user.getId())
                .eq("is_read", 0)
                .update();
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result markAllRead() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        int updated = baseMapper.update(null, new LambdaUpdateWrapper<Notification>()
                .set(Notification::getIsRead, 1)
                .eq(Notification::getUserId, user.getId())
                .eq(Notification::getIsRead, 0));
        return Result.ok(updated);
    }
}
