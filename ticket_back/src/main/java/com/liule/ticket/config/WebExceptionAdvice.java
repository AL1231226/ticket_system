package com.liule.ticket.config;

import com.liule.ticket.dto.Result;
import com.liule.ticket.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 业务异常：message 原样返回给前端。
     * <p>
     * 不用管它和下面 {@code RuntimeException} handler 的先后顺序 ——
     * Spring 按异常类型挑最具体的那个，BizException 一定优先命中。
     * <p>
     * 记 warn 不记 error：业务失败是正常路径（用户抢到了但库存不够），
     * 记成 error 会污染日志、让真正的故障淹没在里面。
     */
    @ExceptionHandler(BizException.class)
    public Result handleBizException(BizException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /**
     * Spring 的 multipart 限制会在进入 Controller 出之前抛，这里转成业务提示。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超过限制：{}", e.getMessage());
        return Result.fail("图片不能超过 5MB");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
