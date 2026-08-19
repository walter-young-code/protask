package com.protask.common;

import lombok.Getter;

/**
 * 业务状态码枚举，messageKey 指向 i18n/messages*.properties 中的文案。
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "common.success"),
    BAD_REQUEST(400, "common.badRequest"),
    UNAUTHORIZED(401, "common.unauthorized"),
    FORBIDDEN(403, "common.forbidden"),
    NOT_FOUND(404, "common.notFound"),
    CONFLICT(409, "common.conflict"),
    TOO_MANY_REQUESTS(429, "common.tooManyRequests"),
    INTERNAL_ERROR(500, "common.internalError");

    private final int code;
    private final String messageKey;

    ResultCode(int code, String messageKey) {
        this.code = code;
        this.messageKey = messageKey;
    }
}
