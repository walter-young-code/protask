package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * IcsService 单元测试：ICS 结构、文本转义、行折叠、UTC DTSTAMP 与凭证校验。
 */
@ExtendWith(MockitoExtension.class)
class IcsServiceTest {

    private static final String TOKEN = "token-abc";
    private static final Long PROJECT_ID = 100L;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private BlockMapper blockMapper;

    private IcsService icsService;

    @BeforeEach
    void setUp() {
        icsService = new IcsService(projectMapper, blockMapper);
    }

    /** 已开启订阅的普通项目（含内置 status/dueDate 属性） */
    private Project subscribedProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        project.setCalendarToken(TOKEN);
        project.setStatus("active");
        project.setIsTemplate(false);
        project.setCardProperties("""
                [{"id":"status","type":"select","options":[
                    {"id":"todo","value":"待开始"},
                    {"id":"done","value":"已完成"}]},
                 {"id":"dueDate","type":"date"}]
                """);
        return project;
    }

    /** 构造带 dueDate/status 属性的卡片 */
    private Block card(Long id, String title, String dueDate, String status) {
        Block card = new Block();
        card.setId(id);
        card.setProjectId(PROJECT_ID);
        card.setType(BlockTypes.CARD);
        card.setTitle(title);
        card.setCreateAt(LocalDateTime.of(2026, 8, 1, 0, 0, 0));
        card.setUpdateAt(LocalDateTime.of(2026, 8, 19, 10, 20, 30));
        Map<String, Object> properties = new HashMap<>();
        if (dueDate != null) {
            properties.put("dueDate", dueDate);
        }
        if (status != null) {
            properties.put("status", status);
        }
        card.setFields(Map.of("properties", properties));
        return card;
    }

    @Test
    void generateIcsOutputsAllDayEventsWithUtcStamp() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subscribedProject());
        when(blockMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                card(1L, "任务A", "2026-08-20", "todo"),
                card(2L, "任务B", "2026-08-10", "done")));

        String ics = icsService.generateIcs(TOKEN);

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
        assertTrue(ics.contains("VERSION:2.0"));
        assertTrue(ics.contains("X-WR-CALNAME:示例项目"));
        // 稳定 UID：Outlook 据此更新而非重复创建
        assertTrue(ics.contains("UID:task-100-1@protask"));
        assertTrue(ics.contains("UID:task-100-2@protask"));
        // 全天事件：DTEND 为截止日次日
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260820"));
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260821"));
        // DTSTAMP 为 UTC 字面量格式（与全系统 UTC 存储口径一致）
        assertTrue(ics.contains("DTSTAMP:20260819T102030Z"));
        // 状态选项文案进入描述
        assertTrue(ics.contains("DESCRIPTION:状态：待开始"));
        assertTrue(ics.contains("DESCRIPTION:状态：已完成"));
        // 事件按截止日期升序：任务B（08-10）在任务A（08-20）之前
        assertTrue(ics.indexOf("UID:task-100-2@protask") < ics.indexOf("UID:task-100-1@protask"));
    }

    @Test
    void generateIcsSkipsCardsWithoutDueDate() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(subscribedProject());
        when(blockMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                card(1L, "无日期", null, null),
                card(2L, "非法日期", "not-a-date", null),
                card(3L, "有日期", "2026-09-01", null)));

        String ics = icsService.generateIcs(TOKEN);

        assertTrue(ics.contains("UID:task-100-3@protask"));
        assertFalse(ics.contains("UID:task-100-1@protask"));
        assertFalse(ics.contains("UID:task-100-2@protask"));
    }

    @Test
    void generateIcsRejectsInvalidToken() {
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> icsService.generateIcs("bad-token"));
        assertEquals(ResultCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void generateIcsRejectsArchivedOrTemplateProject() {
        Project archived = subscribedProject();
        archived.setStatus("archived");
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(archived);
        assertThrows(BusinessException.class, () -> icsService.generateIcs(TOKEN));

        Project template = subscribedProject();
        template.setIsTemplate(true);
        when(projectMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(template);
        assertThrows(BusinessException.class, () -> icsService.generateIcs(TOKEN));
    }

    @Test
    void escapeTextEscapesSpecialCharacters() {
        assertEquals("a\\,b\\;c\\\\d\\ne", IcsService.escapeText("a,b;c\\d\ne"));
        assertEquals("普通标题", IcsService.escapeText("普通标题"));
    }

    @Test
    void foldLineKeepsShortLinesUnchanged() {
        assertEquals("SUMMARY:短标题", IcsService.foldLine("SUMMARY:短标题"));
    }

    @Test
    void foldLineSplitsLongLinesWithin75Octets() {
        String longLine = "SUMMARY:" + "测".repeat(100);
        String folded = IcsService.foldLine(longLine);
        String[] lines = folded.split("\r\n");
        assertTrue(lines.length > 1);
        for (String line : lines) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75);
        }
        // 续行以空格开头（RFC 5545 折叠规则）
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].startsWith(" "));
        }
        // 折叠后去掉续行前导空格可还原原文（未在多字节字符中间截断）
        String unfolded = folded.replace("\r\n ", "");
        assertEquals(longLine, unfolded);
    }
}
