package com.protask.dto;

import lombok.Data;

/**
 * 项目日历订阅视图：订阅凭证与完整 ICS 订阅地址。
 * subscribeUrl 基于请求自身 scheme + host 拼接，供复制到 Outlook 等日历客户端订阅。
 */
@Data
public class CalendarSubscriptionVO {

    /** 订阅凭证(UUID，null=未开启订阅) */
    private String calendarToken;

    /** ICS 订阅地址（null=未开启订阅） */
    private String subscribeUrl;
}
