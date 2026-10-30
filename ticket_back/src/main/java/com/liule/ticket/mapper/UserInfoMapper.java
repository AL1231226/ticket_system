package com.liule.ticket.mapper;

import com.liule.ticket.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserInfoMapper extends BaseMapper<UserInfo> {

    /** 粉丝数 +1；用户详情行缺失时兜底插入，避免计数静默丢失 */
    @Insert("INSERT INTO tb_user_info (user_id, fans, followee) VALUES (#{userId}, 1, 0) " +
            "ON DUPLICATE KEY UPDATE fans = fans + 1")
    int incrementFans(@Param("userId") Long userId);

    /** 关注数 +1；用户详情行缺失时兜底插入 */
    @Insert("INSERT INTO tb_user_info (user_id, fans, followee) VALUES (#{userId}, 0, 1) " +
            "ON DUPLICATE KEY UPDATE followee = followee + 1")
    int incrementFollowee(@Param("userId") Long userId);

    /** 粉丝数 -1，GREATEST 兜底避免负数 */
    @Update("UPDATE tb_user_info SET fans = GREATEST(fans - 1, 0) WHERE user_id = #{userId}")
    int decrementFans(@Param("userId") Long userId);

    /** 关注数 -1，GREATEST 兜底避免负数 */
    @Update("UPDATE tb_user_info SET followee = GREATEST(followee - 1, 0) WHERE user_id = #{userId}")
    int decrementFollowee(@Param("userId") Long userId);
}
