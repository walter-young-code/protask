package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ICS（iCalendar）订阅服务：按订阅凭证生成项目的日历文件。
 * 任务即 type=card 的块，事件日期取内置 dueDate 属性（纯日期，无时区语义）；
 * DTSTAMP 按 UTC 输出，与全系统 UTC 存储口径一致。
 */
@Service
@RequiredArgsConstructor
public class IcsService {

    /** 内置截止日期属性 id（对齐前端 BUILTIN_PROPERTIES） */
    private static final String PROP_DUE_DATE = "dueDate";

    /** 内置状态属性 id（用于事件描述展示状态） */
    private static final String PROP_STATUS = "status";

    /** DTSTAMP 格式：UTC 字面量 Z 后缀（RFC 5545 UTC 时间形式） */
    private static final DateTimeFormatter DTSTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** RFC 5545 单行最大长度（octets） */
    private static final int MAX_LINE_OCTETS = 75;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProjectMapper projectMapper;
    private final BlockMapper blockMapper;

    /**
     * 按订阅凭证生成 ICS 文本。凭证无效/项目已删除/模板/已归档时返回 404。
     * 包含全部任务（含已完成），每个任务为 dueDate 当天的全天事件。
     */
    public String generateIcs(String token) {
        Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getCalendarToken, token));
        if (project == null || project.getDeleteAt() != null || Boolean.TRUE.equals(project.getIsTemplate())
                || "archived".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.calendarNotFound");
        }
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, project.getId())
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
        Map<String, String> statusOptions = parseStatusOptions(project.getCardProperties());

        StringBuilder sb = new StringBuilder();
        appendLine(sb, "BEGIN:VCALENDAR");
        appendLine(sb, "VERSION:2.0");
        appendLine(sb, "PRODID:-//ProTask//Calendar Subscription//CN");
        appendLine(sb, "CALSCALE:GREGORIAN");
        appendLine(sb, "X-WR-CALNAME:" + escapeText(project.getName()));

        List<Block> datedCards = new ArrayList<>();
        for (Block card : cards) {
            if (parseDate(asString(currentProperties(card).get(PROP_DUE_DATE))) != null) {
                datedCards.add(card);
            }
        }
        // 按截止日期升序输出，保证内容稳定可比较
        datedCards.sort(Comparator.comparing(
                card -> parseDate(asString(currentProperties(card).get(PROP_DUE_DATE)))));
        for (Block card : datedCards) {
            appendEvent(sb, project.getId(), card, statusOptions);
        }
        appendLine(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** 输出单个任务的全天事件（DTEND 为次日，符合 RFC 5545 全天事件语义） */
    private void appendEvent(StringBuilder sb, Long projectId, Block card,
                             Map<String, String> statusOptions) {
        LocalDate due = parseDate(asString(currentProperties(card).get(PROP_DUE_DATE)));
        LocalDateTime stamp = card.getUpdateAt() != null ? card.getUpdateAt() : card.getCreateAt();
        appendLine(sb, "BEGIN:VEVENT");
        // 稳定 UID：Outlook 据此识别同一事件并更新，而非重复创建
        appendLine(sb, "UID:task-" + projectId + "-" + card.getId() + "@protask");
        appendLine(sb, "DTSTAMP:" + (stamp != null ? stamp.format(DTSTAMP_FORMAT) : "19700101T000000Z"));
        appendLine(sb, "DTSTART;VALUE=DATE:" + due.format(DATE_FORMAT));
        appendLine(sb, "DTEND;VALUE=DATE:" + due.plusDays(1).format(DATE_FORMAT));
        appendLine(sb, "SUMMARY:" + escapeText(card.getTitle() != null ? card.getTitle() : ""));
        String description = buildDescription(card, statusOptions);
        if (description != null) {
            appendLine(sb, "DESCRIPTION:" + escapeText(description));
        }
        // 全天任务不占用日程忙闲
        appendLine(sb, "TRANSP:TRANSPARENT");
        appendLine(sb, "END:VEVENT");
    }

    /** 事件描述：含内置状态选项文案（项目未添加状态属性时省略） */
    private String buildDescription(Block card, Map<String, String> statusOptions) {
        if (statusOptions.isEmpty()) {
            return null;
        }
        String statusValue = asString(currentProperties(card).get(PROP_STATUS));
        String label = statusOptions.get(statusValue);
        return label != null ? "状态：" + label : null;
    }

    /**
     * 解析项目 cardProperties 中内置 status 属性的选项映射（选项 id -> 文案）。
     * 未添加状态属性或 JSON 非法时返回空 Map。
     */
    private Map<String, String> parseStatusOptions(String cardPropertiesJson) {
        Map<String, String> options = new HashMap<>();
        if (cardPropertiesJson == null || cardPropertiesJson.isBlank()) {
            return options;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(cardPropertiesJson);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (!PROP_STATUS.equals(node.path("id").asText(null))) {
                        continue;
                    }
                    JsonNode optionNodes = node.path("options");
                    if (optionNodes.isArray()) {
                        for (JsonNode option : optionNodes) {
                            String id = option.path("id").asText(null);
                            String value = option.path("value").asText(null);
                            if (id != null && value != null) {
                                options.put(id, value);
                            }
                        }
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 按无状态属性处理
        }
        return options;
    }

    /** 解析截止日期（仅取前 10 位 YYYY-MM-DD，兼容存量带时间的值），非法返回 null */
    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentProperties(Block card) {
        if (card.getFields() == null) {
            return Map.of();
        }
        Object properties = card.getFields().get("properties");
        return properties instanceof Map ? (Map<String, Object>) properties : Map.of();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /** ICS 文本转义（RFC 5545）：反斜杠、分号、逗号与换行 */
    static String escapeText(String text) {
        return text.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /** 追加一行：先按 75 octets 折叠（续行以空格开头），再补 CRLF 行尾 */
    private void appendLine(StringBuilder sb, String line) {
        sb.append(foldLine(line)).append("\r\n");
    }

    /** 按 UTF-8 字节数折叠长行，避免在多字节字符中间截断 */
    static String foldLine(String line) {
        if (line.getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_OCTETS) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        StringBuilder current = new StringBuilder();
        int currentOctets = 0;
        for (int i = 0; i < line.length(); ) {
            int codePoint = line.codePointAt(i);
            String ch = new String(Character.toChars(codePoint));
            int octets = ch.getBytes(StandardCharsets.UTF_8).length;
            if (currentOctets + octets > MAX_LINE_OCTETS) {
                out.append(current).append("\r\n");
                current = new StringBuilder(" ");
                currentOctets = 1;
            }
            current.append(ch);
            currentOctets += octets;
            i += Character.charCount(codePoint);
        }
        out.append(current);
        return out.toString();
    }
}
