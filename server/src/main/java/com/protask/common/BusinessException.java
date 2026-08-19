package com.protask.common;

import lombok.Getter;

/**
 * 业务异常，携带业务状态码与国际化消息 key，由全局异常处理器按请求语言统一转换为 Result 响应。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    /** 国际化消息 key（i18n/messages*.properties），为 null 时使用 message 字面量 */
    private final String messageKey;

    /** 消息占位符参数 */
    private final Object[] args;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessageKey());
        this.code = resultCode.getCode();
        this.messageKey = resultCode.getMessageKey();
        this.args = new Object[0];
    }

    public BusinessException(ResultCode resultCode, String messageKey, Object... args) {
        super(messageKey);
        this.code = resultCode.getCode();
        this.messageKey = messageKey;
        this.args = args;
    }

    public BusinessException(int code, String messageKey, Object... args) {
        super(messageKey);
        this.code = code;
        this.messageKey = messageKey;
        this.args = args;
    }
}
