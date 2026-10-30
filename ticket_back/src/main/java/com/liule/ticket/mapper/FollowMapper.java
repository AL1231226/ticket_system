package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.Follow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowMapper extends BaseMapper<Follow> {

    /** 唯一索引兜底：已关注时返回 0，不抛 DuplicateKeyException */
    @Insert("INSERT IGNORE INTO tb_follow(user_id, follow_user_id, create_time) " +
            "VALUES(#{userId}, #{followUserId}, NOW())")
    int insertIgnore(@Param("userId") Long userId, @Param("followUserId") Long followUserId);

    /** Redis Set 丢失时的共同关注兜底 */
    @Select("SELECT f1.follow_user_id FROM tb_follow f1 " +
            "JOIN tb_follow f2 ON f1.follow_user_id = f2.follow_user_id " +
            "WHERE f1.user_id = #{me} AND f2.user_id = #{other}")
    List<Long> selectCommon(@Param("me") Long me, @Param("other") Long other);
}
