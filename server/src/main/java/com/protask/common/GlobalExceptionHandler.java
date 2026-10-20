package com.protask.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器，将异常统一转换为 {@link Result} 结构。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 单文件上传上限（与 application.yml 的 multipart 配置一致，提示文案动态引用） */
    @Value("${spring.servlet.multipart.max-file-size:5MB}")
    private String maxFileSize;

    /** 业务异常：按请求语言解析国际化消息 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        String message = MessageUtil.get(e.getMessageKey(), e.getArgs());
        return Result.fail(e.getCode(), message);
    }

    /** Sa-Token 未登录 */
    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLogin(NotLoginException e) {
        return Result.fail(ResultCode.UNAUTHORIZED);
    }

    /** Sa-Token 缺少角色 / 权限 */
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public Result<Void> handleNotPermission(Exception e) {
        return Result.fail(ResultCode.FORBIDDEN);
    }

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null
                ? fieldError.getDefaultMessage()
                : MessageUtil.get("common.validationFailed");
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), message);
    }

    /** 请求体无法解析（畸形 JSON / 类型不匹配）：返回 400 而非兜底 500 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), MessageUtil.get("common.validationFailed"));
    }

    /** 上传文件超过大小限制：提示上限值，友好提示而非兜底 500 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return Result.fail(ResultCode.BAD_REQUEST.getCode(), MessageUtil.get("upload.fileTooLarge", maxFileSize));
    }

    /** 静态资源不存在（如图片已被删除）：返回 404 而非兜底 500 */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}
