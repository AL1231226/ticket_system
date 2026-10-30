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
@TableName("tb_post_comment")
public class PostComment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long postId;
    private Long userId;
    /** v1 恒为 0，保留给以后盖楼 */
    private Long parentId;
    private Long answerId;
    private String content;
    private Integer liked;
    /** 0 正常 / 1 折叠（软删除） */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
