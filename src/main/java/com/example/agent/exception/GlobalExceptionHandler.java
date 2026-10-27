package com.example.agent.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 使用 Spring 自带的 ProblemDetail 返回基础请求错误，暂不设计复杂异常体系。
 * 模型、工具和 Agent 的异常处理在对应学习阶段再加入。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        String detail = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        logger.warn("聊天请求参数校验失败：{}", detail);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException exception) {
        logger.warn("聊天请求体读取失败：{}", exception.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求体必须是合法的 JSON 对象");
    }

    @ExceptionHandler(DifyCallException.class)
    public ProblemDetail handleDifyCall(DifyCallException exception) {

        logger.error("Dify 工作流调用失败", exception);

        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY,
                "智能体服务暂时不可用，请稍后重试"
        );
    }
}
