package com.liule.ticket.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.liule.ticket.dto.CommentCreateDTO;
import com.liule.ticket.dto.FeedCursor;
import com.liule.ticket.dto.Result;
import com.liule.ticket.entity.PostComment;

/**
 * 动态评论（v1 只有一级评论）。
 */
public interface ICommentService extends IService<PostComment> {

    /** 发评论：登录、正文 1–512 字、动态必须正常上架 */
    Result publish(CommentCreateDTO dto);

    /** 某动态的评论，时间倒序，游标分页，公开接口 */
    Result ofPost(Long postId, FeedCursor cursor, int count);

    /** 软删除，仅评论作者本人 */
    Result delete(Long commentId);
}
