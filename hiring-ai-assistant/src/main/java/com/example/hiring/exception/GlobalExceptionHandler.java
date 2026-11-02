package com.example.hiring.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * API 层：全局异常到 HTTP 语义的统一翻译（RFC 7807 ProblemDetail）。
 *
 * <p>业务层抛 {@link HiringException}（自带 status），HTTP 语义在这里集中收口：
 * 成功路径保持裸 JSON 不包壳，错误路径返回标准 ProblemDetail
 * （{@code Content-Type: application/problem+json}，字段 status/title/detail）。
 *
 * <pre>
 * HiringException             → 自带 status（404 查无资源 / 400 入参不合法 / 409 状态冲突）
 * JSON 请求体解析失败         → 400  （HttpMessageNotReadableException，ErrorResponse 的例外）
 * Spring MVC 自带异常         → 原样保留（参数校验 400、方法不支持 405 等）
 * IllegalArgumentException    → 400  尚未收编的裸抛
 * IllegalStateException       → 409  尚未收编的裸抛
 * 其余异常                    → 500  通用消息 + error 日志，不泄内部细节
 * </pre>
 *
 * @author hengdboy
 * @since v0.31
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HiringException.class)
    ProblemDetail handleHiring(HiringException ex) {
        log.info("[Api] {} {}", ex.getStatus().value(), ex.getMessage());
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        // 它是 ErrorResponse 的著名例外（Spring 6 未实现），不接住会落进兜底变 500
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体不是合法 JSON");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.info("[Api] 400 {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleConflict(IllegalStateException ex) {
        log.info("[Api] 409 {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnknown(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            // Spring MVC 自带异常（参数校验、405 等）自带语义与状态码，
            // 原样放行，避免被兜底分支吞成 500
            return er.getBody();
        }
        log.error("[Api] 500 未处理异常", ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "服务内部错误，请稍后重试");
    }
}
