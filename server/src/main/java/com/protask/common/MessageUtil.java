package com.protask.common;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * 国际化工具：按请求 Locale（Accept-Language 请求头）解析消息文案。
 */
@Component
public class MessageUtil implements MessageSourceAware {

    private static MessageSource messageSource;

    @Override
    public void setMessageSource(MessageSource messageSource) {
        MessageUtil.messageSource = messageSource;
    }

    /** 解析消息，找不到 key 时原样返回 key，便于排查 */
    public static String get(String key, Object... args) {
        if (messageSource == null) {
            return key;
        }
        try {
            return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return key;
        }
    }
}
