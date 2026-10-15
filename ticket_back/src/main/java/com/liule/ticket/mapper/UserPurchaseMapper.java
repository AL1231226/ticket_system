package com.liule.ticket.mapper;

import com.liule.ticket.entity.UserPurchase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserPurchaseMapper extends BaseMapper<UserPurchase> {
    //某用户在某场次购买的票数的累加
    @Insert("INSERT INTO tb_user_purchase (user_id, session_id, quantity) " +
            "VALUES (#{userId}, #{sessionId}, #{quantity}) AS new " +
            "ON DUPLICATE KEY UPDATE quantity = tb_user_purchase.quantity + new.quantity")
    int increase(@Param("userId") Long userId,
                 @Param("sessionId") Long sessionId,
                 @Param("quantity") Integer quantity);



     // 查已购数量，没有记录时返回 null

    @Select("SELECT quantity FROM tb_user_purchase WHERE user_id = #{userId} AND session_id = #{sessionId}")
    Integer getQuantity(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    // 取消/退票时把已购数减回去
    @Update("UPDATE tb_user_purchase SET quantity = quantity - #{quantity} " +
            "WHERE user_id = #{userId} AND session_id = #{sessionId} AND quantity >= #{quantity}")
    int decrease(@Param("userId") Long userId,
                 @Param("sessionId") Long sessionId,
                 @Param("quantity") Integer quantity);



}
