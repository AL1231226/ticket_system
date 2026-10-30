package com.liule.ticket.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.liule.ticket.dto.LoginFormDTO;
import com.liule.ticket.dto.Result;
import com.liule.ticket.dto.UserDTO;
import com.liule.ticket.dto.UserProfileVO;
import com.liule.ticket.entity.Post;
import com.liule.ticket.entity.User;
import com.liule.ticket.entity.UserInfo;
import com.liule.ticket.mapper.PostMapper;
import com.liule.ticket.mapper.UserMapper;
import com.liule.ticket.service.IFollowService;
import com.liule.ticket.service.IUserInfoService;
import com.liule.ticket.service.IUserService;
import com.liule.ticket.utils.RegexUtils;
import com.liule.ticket.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.liule.ticket.utils.RedisConstants.*;
import static com.liule.ticket.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private PostMapper postMapper;

    @Resource
    private IFollowService followService;

    @Override
    public Result sendcode(String phone) {
        // 验证手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        // 生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 存入Redis，设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 发送验证码（模拟）
        log.debug("发送验证码成功：{}", code);
        return Result.ok();
    }

    @Override
    public Result queryPublicProfile(Long userId) {
        if (userId == null) {
            return Result.fail("用户不存在");
        }
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        UserInfo info = userInfoService.getById(userId);
        UserProfileVO vo = new UserProfileVO();
        vo.setUserId(userId);
        vo.setNickname(user.getNickname());
        vo.setIcon(user.getIcon());
        if (info == null) {
            // 历史/测试用户可能没有详情行，给展示默认值而不是报错
            vo.setIntroduce("");
            vo.setCity("");
            vo.setGender(0);
            vo.setLevel(1);
            vo.setShowCount(0);
            vo.setFans(0);
            vo.setFollowee(0);
        } else {
            vo.setIntroduce(info.getIntroduce() == null ? "" : info.getIntroduce());
            vo.setCity(info.getCity() == null ? "" : info.getCity());
            vo.setGender(info.getGender() == null ? 0 : info.getGender());
            vo.setLevel(info.getLevel() == null ? 1 : info.getLevel());
            vo.setShowCount(info.getShowCount() == null ? 0 : info.getShowCount());
            vo.setFans(info.getFans() == null ? 0 : info.getFans());
            vo.setFollowee(info.getFollowee() == null ? 0 : info.getFollowee());
        }

        Long posts = postMapper.selectCount(new LambdaQueryWrapper<Post>()
                .eq(Post::getUserId, userId)
                .eq(Post::getStatus, 0));
        vo.setPosts(posts == null ? 0L : posts);

        UserDTO me = UserHolder.getUser();
        boolean isFollowing = me != null && me.getId() != null
                && !me.getId().equals(userId)
                && followService.isFollowing(me.getId(), userId);
        vo.setIsFollowing(isFollowing);

        return Result.ok(vo);
    }

    @Override
    public Result logout(String token) {
        // 删除Redis中的登录信息，实现登出
        if (token != null && !token.isEmpty()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    @Override
    public Result sign() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取今天的日期
        LocalDateTime now = LocalDateTime.now();
        //日期格式
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        //今天
        int dayOfMonth = now.getDayOfMonth();
        //key
        String key = USER_SIGN_KEY + userId + date;
        //签到
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok("签到成功");
    }

    @Override
    public Result signCount() {
        //获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //获取今天的日期
        LocalDateTime now = LocalDateTime.now();
        //日期格式
        String date = now.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        //今天
        int dayOfMonth = now.getDayOfMonth();
        //key
        String key = USER_SIGN_KEY + userId + date;
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((num & 1) == 0) {
                break;
            }
            count++;
            num >>= 1;
        }
        return Result.ok(count);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result login(LoginFormDTO loginForm) {
        // 验证手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        String cacheCode = stringRedisTemplate.opsForValue()
                .getAndDelete(LOGIN_CODE_KEY + loginForm.getPhone());
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }
        // 根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        if (user == null) {
            user = createuser(loginForm.getPhone());
        }
        // 生成token
        String token = UUID.randomUUID().toString(true);
        // User → UserDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // UserDTO → Map，value全部转为String
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, String> stringMap = new HashMap<>();
        userMap.forEach((k, v) -> stringMap.put(k, v.toString()));
        // 存入Redis（Hash结构）
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, stringMap);
        // 设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 返回token
        return Result.ok(token);
    }


    private User createuser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickname(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        // 预初始化用户详情（昵称以外的字段全部落库默认值）
        userInfoService.save(new UserInfo().setUserId(user.getId()));
        return user;
    }
}
