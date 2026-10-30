package com.liule.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_post")
public class Post implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long showId;
    private Long sessionId;
    private String title;
    private String content;
    /** 逗号分隔的图片 URL，最多 9 张 */
    private String images;
    private Integer liked;
    private Integer comments;
    /** 0 正常 / 1 下架 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
