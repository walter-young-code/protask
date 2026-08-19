package com.protask.controller;

import com.protask.common.BusinessException;
import com.protask.service.IcsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开日历订阅接口：Outlook 等客户端凭订阅地址中的凭证匿名拉取 ICS，
 * 不经过登录拦截（SaTokenConfig 放行 /api/v1/public/calendars/**）。
 */
@RestController
@RequestMapping("/api/v1/public/calendars")
@RequiredArgsConstructor
public class CalendarController {

    private final IcsService icsService;

    /** 按订阅凭证返回项目的 iCalendar 文件（凭证无效返回真实 HTTP 404） */
    @GetMapping(value = "/{token}.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> subscribe(@PathVariable String token) {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                    .body(icsService.generateIcs(token));
        } catch (BusinessException e) {
            // Outlook 等外部客户端按 HTTP 状态码判断订阅有效性，
            // 不能依赖全局异常处理器的 200 + 业务码 Result 包装
            int status = e.getCode() >= 400 && e.getCode() < 600 ? e.getCode() : 500;
            return ResponseEntity.status(status).build();
        }
    }
}
