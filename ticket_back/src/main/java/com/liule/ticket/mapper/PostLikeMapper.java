package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.PostLike;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface PostLikeMapper extends BaseMapper<PostLike> {

    /** 唯一索引兜底：已点赞时返回 0，不抛 DuplicateKeyException */
    @Insert("INSERT IGNORE INTO tb_post_like(post_id, user_id, create_time) " +
            "VALUES(#{postId}, #{userId}, NOW())")
    int insertIgnore(@Param("postId") Long postId, @Param("userId") Long userId);
}
