package com.liule.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.liule.ticket.entity.Post;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PostMapper extends BaseMapper<Post> {

    /** 数据库原子自增，避免先查再写在并发下丢计数 */
    @Update("UPDATE tb_post SET liked = liked + 1 WHERE id = #{postId}")
    int incrementLiked(@Param("postId") Long postId);

    /** 取消点赞，GREATEST 兜底避免计数变成负数 */
    @Update("UPDATE tb_post SET liked = GREATEST(liked - 1, 0) WHERE id = #{postId}")
    int decrementLiked(@Param("postId") Long postId);

    /** 新增评论后原子自增评论数 */
    @Update("UPDATE tb_post SET comments = comments + 1 WHERE id = #{postId}")
    int incrementComments(@Param("postId") Long postId);

    /** 删除评论后原子回减评论数，GREATEST 兜底避免计数变成负数 */
    @Update("UPDATE tb_post SET comments = GREATEST(comments - 1, 0) WHERE id = #{postId}")
    int decrementComments(@Param("postId") Long postId);
}
