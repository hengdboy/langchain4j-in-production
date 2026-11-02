package com.example.hiring.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带期望的 HTTP 状态码，由 {@link GlobalExceptionHandler} 统一翻译为
 * RFC 7807 ProblemDetail。
 *
 * <p>status 的惯用取值：404 查无此资源、400 入参不合法（引用已关闭的职位、否决没给理由）、
 * 409 与当前状态冲突（重复投递、状态机拒绝）。
 *
 * @author hengdboy
 * @since v0.31
 */
@Getter
public class HiringException extends RuntimeException {

    private final HttpStatus status;

    public HiringException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
