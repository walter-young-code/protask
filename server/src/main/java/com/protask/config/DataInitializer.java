package com.protask.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BusinessException;
import com.protask.common.PasswordStrengthValidator;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 初始化数据：
 * 1) 首次启动时若用户表为空，则创建管理员账号：
 *    密码取 PROTASK_ADMIN_PASSWORD；未配置时随机生成并在日志中打印一次。
 * 2) 首次启动时若模板库为空，则导入内置项目模板（参考 Focalboard 模板集，
 *    模板以 is_template=1 的项目承载卡片属性定义与示例任务/视图）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final ProjectMapper projectMapper;
    private final BlockMapper blockMapper;
    private final PasswordEncoder passwordEncoder;

    /** 初始管理员密码（可选）：未配置时随机生成并仅在首次启动日志打印一次 */
    @Value("${protask.admin.initial-password:}")
    private String initialAdminPassword;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 随机密码字符集：剔除易混淆字符（0/O、1/I/l 等） */
    private static final String PASSWORD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final String PASSWORD_SYMBOLS = "!@#$%^&*";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 内置模板的视图块标题与卡片图标 */
    private static final String BOARD_VIEW_TITLE = "看板";
    private static final String TABLE_VIEW_TITLE = "表格";

    @Override
    public void run(String... args) {
        Long systemUserId = ensureSystemUser();
        if (systemUserId != null) {
            initTemplates(systemUserId);
        }
    }

    /** 确保存在系统用户（优先 admin 角色，否则取首个用户），返回用户 ID */
    private Long ensureSystemUser() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            User admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getRole, "admin").last("LIMIT 1"));
            if (admin != null) {
                return admin.getId();
            }
            User first = userMapper.selectOne(new LambdaQueryWrapper<User>().last("LIMIT 1"));
            return first != null ? first.getId() : null;
        }
        User admin = new User();
        admin.setUsername("admin");
        String password = resolveInitialPassword();
        admin.setPassword(passwordEncoder.encode(password));
        admin.setNickname("管理员");
        admin.setRole("admin");
        admin.setStatus(1);
        userMapper.insert(admin);
        if (initialAdminPassword != null && !initialAdminPassword.isBlank()) {
            log.info("已创建默认管理员账号 admin（密码来自 PROTASK_ADMIN_PASSWORD 配置）");
        } else {
            log.warn("已创建默认管理员账号 admin，随机初始密码（仅本次启动打印，请登录后立即修改）: {}", password);
        }
        return admin.getId();
    }

    /** 解析初始密码：优先环境变量配置（需满足强度要求），否则随机生成 16 位强密码 */
    private String resolveInitialPassword() {
        if (initialAdminPassword == null || initialAdminPassword.isBlank()) {
            return generateRandomPassword();
        }
        try {
            PasswordStrengthValidator.validate(initialAdminPassword);
        } catch (BusinessException e) {
            throw new IllegalStateException(
                    "PROTASK_ADMIN_PASSWORD 不符合密码强度要求（至少 8 位且包含大小写字母/数字/特殊字符中的 3 类）", e);
        }
        return initialAdminPassword;
    }

    /** 生成包含四类字符的 16 位随机密码，并打乱顺序 */
    private static String generateRandomPassword() {
        StringBuilder sb = new StringBuilder(16);
        sb.append(randomCharOf(PASSWORD_UPPER))
                .append(randomCharOf(PASSWORD_LOWER))
                .append(randomCharOf(PASSWORD_DIGITS))
                .append(randomCharOf(PASSWORD_SYMBOLS));
        String all = PASSWORD_UPPER + PASSWORD_LOWER + PASSWORD_DIGITS + PASSWORD_SYMBOLS;
        while (sb.length() < 16) {
            sb.append(randomCharOf(all));
        }
        char[] chars = sb.toString().toCharArray();
        for (int i = chars.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char randomCharOf(String source) {
        return source.charAt(SECURE_RANDOM.nextInt(source.length()));
    }

    /** 模板库为空时导入内置模板（项目 + 视图块 + 示例任务块） */
    private void initTemplates(Long systemUserId) {
        Long count = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsTemplate, true));
        if (count != null && count > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (TemplateSeed seed : TEMPLATES) {
            Project template = new Project();
            template.setName(seed.name());
            template.setDescription(seed.description());
            template.setType("O");
            template.setIcon(seed.icon());
            template.setCardProperties(writeJson(seed.cardProperties()));
            template.setIsTemplate(true);
            template.setOwnerId(systemUserId);
            template.setCreatedBy(systemUserId);
            template.setModifiedBy(systemUserId);
            template.setStatus("active");
            template.setCreatedAt(now);
            template.setUpdatedAt(now);
            projectMapper.insert(template);

            insertBlock(template.getId(), "view", seed.viewTitle(), seed.viewFields(), systemUserId, now);
            for (CardSeed card : seed.cards()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                fields.put("icon", card.icon() != null ? card.icon() : "");
                fields.put("description", "");
                fields.put("contentOrder", List.of());
                fields.put("properties", card.properties());
                insertBlock(template.getId(), "card", card.title(), fields, systemUserId, now);
            }
        }
        log.info("已初始化 {} 个内置项目模板", TEMPLATES.size());
    }

    private void insertBlock(Long projectId, String type, String title,
                             Map<String, Object> fields, Long userId, LocalDateTime now) {
        Block block = new Block();
        block.setProjectId(projectId);
        block.setSchemaVer(1L);
        block.setType(type);
        block.setTitle(title);
        block.setFields(fields);
        block.setCreatedBy(userId);
        block.setModifiedBy(userId);
        block.setCreateAt(now);
        block.setUpdateAt(now);
        blockMapper.insert(block);
    }

    private String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("模板卡片属性序列化失败", e);
        }
    }

    // ---------- 内置模板定义 ----------

    /** 内置模板种子：名称/描述/图标/属性定义/视图/示例任务 */
    private record TemplateSeed(String name, String description, String icon,
                                List<Map<String, Object>> cardProperties,
                                String viewTitle, String viewType, String groupById,
                                List<CardSeed> cards) {
        Map<String, Object> viewFields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("viewType", viewType);
            fields.put("filters", List.of());
            fields.put("sorts", List.of());
            fields.put("groupById", groupById);
            fields.put("dateId", null);
            fields.put("cardPropertyIds", null);
            return fields;
        }
    }

    /** 示例任务种子 */
    private record CardSeed(String title, String icon, Map<String, Object> properties) {
    }

    private static final List<TemplateSeed> TEMPLATES = List.of(
            new TemplateSeed("销售管道 CRM", "管理销售线索与商机，跟踪从初次接触到赢单的完整管道。",
                    "📈", List.of(
                    prop("stage", "阶段", "select", Map.of(
                            "new_lead", "新线索", "following", "跟进中", "quoted", "报价中",
                            "won", "赢单", "lost", "输单")),
                    prop("company", "客户公司", "text", null),
                    prop("amount", "预计金额", "number", null),
                    prop("expectedDate", "预计成交日期", "date", null),
                    prop("owner", "负责人", "member", null)),
                    BOARD_VIEW_TITLE, "board", "stage",
                    List.of(
                            new CardSeed("官网注册客户跟进", "📇", Map.of("stage", "following", "company", "星辰科技", "amount", 80000)),
                            new CardSeed("年度订阅合同签约", "📝", Map.of("stage", "quoted", "company", "云帆网络", "amount", 120000)),
                            new CardSeed("已赢单：数据看板项目", "🎉", Map.of("stage", "won", "company", "天工智造", "amount", 58000)))),

            new TemplateSeed("公司目标与 OKR", "对齐团队目标：将公司级目标（Objective）拆解为可衡量的关键结果（Key Result）。",
                    "⛳", List.of(
                    prop("category", "类型", "select", Map.of(
                            "objective", "目标", "key_result", "关键结果")),
                    prop("quarter", "季度", "select", Map.of(
                            "Q1", "Q1", "Q2", "Q2", "Q3", "Q3", "Q4", "Q4")),
                    prop("progress", "进度(%)", "number", null),
                    prop("dueDate", "截止日期", "date", null),
                    prop("owner", "负责人", "member", null)),
                    TABLE_VIEW_TITLE, "table", null,
                    List.of(
                            new CardSeed("提升用户满意度至 90 分", "🎯", Map.of("category", "objective", "quarter", "Q3", "progress", 40)),
                            new CardSeed("完成 3 次用户访谈并输出报告", "📊", Map.of("category", "key_result", "quarter", "Q3")),
                            new CardSeed("缺陷修复及时率达到 95%", "✅", Map.of("category", "key_result", "quarter", "Q3", "progress", 70)))),

            new TemplateSeed("敏捷迭代计划", "规划迭代（Sprint）任务：从待办到完成的看板流转，支持故事点数与优先级。",
                    "🗓️", List.of(
                    prop("status", "状态", "select", Map.of(
                            "todo", "未开始", "in_progress", "进行中", "done", "已完成")),
                    prop("sprint", "迭代", "select", Map.of(
                            "sprint1", "Sprint 1", "sprint2", "Sprint 2", "sprint3", "Sprint 3")),
                    prop("type", "类型", "select", Map.of(
                            "epic", "Epic", "feature", "功能", "bug", "缺陷")),
                    prop("priority", "优先级", "select", Map.of(
                            "p1", "P1", "p2", "P2", "p3", "P3")),
                    prop("points", "故事点数", "number", null),
                    prop("dueDate", "截止日期", "date", null),
                    prop("assignee", "负责人", "member", null)),
                    BOARD_VIEW_TITLE, "board", "status",
                    List.of(
                            new CardSeed("实现看板拖拽换列", "🚀", Map.of("status", "in_progress", "sprint", "sprint1", "type", "feature", "priority", "p1", "points", 5)),
                            new CardSeed("修复搜索中文乱码", "🐛", Map.of("status", "todo", "sprint", "sprint1", "type", "bug", "priority", "p2", "points", 2)),
                            new CardSeed("完成登录模块重构", "✅", Map.of("status", "done", "sprint", "sprint2", "type", "feature", "priority", "p2", "points", 8)))),

            new TemplateSeed("用户研究", "组织访谈、问卷与可用性测试，沉淀用户洞察支撑产品决策。",
                    "🔬", List.of(
                    prop("researchType", "研究类型", "select", Map.of(
                            "interview", "用户访谈", "survey", "问卷", "usability", "可用性测试")),
                    prop("status", "状态", "select", Map.of(
                            "planned", "计划中", "recruiting", "招募中", "running", "进行中", "done", "已完成")),
                    prop("participants", "参与者", "number", null),
                    prop("date", "研究日期", "date", null),
                    prop("notes", "要点记录", "text", null)),
                    TABLE_VIEW_TITLE, "table", null,
                    List.of(
                            new CardSeed("新用户首次使用访谈", "🎤", Map.of("researchType", "interview", "status", "recruiting", "participants", 5)),
                            new CardSeed("满意度问卷投放", "📝", Map.of("researchType", "survey", "status", "planned", "participants", 100)),
                            new CardSeed("注册流程可用性测试", "🖱️", Map.of("researchType", "usability", "status", "done", "participants", 8)))),

            new TemplateSeed("竞品分析", "追踪主要竞品的定位、优劣势与动态，支撑产品决策。",
                    "🗂️", List.of(
                    prop("competitor", "竞品", "text", null),
                    prop("category", "类别", "select", Map.of(
                            "direct", "直接", "indirect", "间接", "potential", "潜在")),
                    prop("strengths", "优势", "text", null),
                    prop("weaknesses", "劣势", "text", null),
                    prop("rating", "综合评分", "number", null),
                    prop("status", "跟进状态", "select", Map.of(
                            "open", "未开始", "tracking", "跟进中", "archived", "已归档"))),
                    TABLE_VIEW_TITLE, "table", null,
                    List.of(
                            new CardSeed("竞品 A 深度体验", "🔍", Map.of("competitor", "竞品 A", "category", "direct", "rating", 8, "status", "tracking")),
                            new CardSeed("竞品 B 功能清单", "📋", Map.of("competitor", "竞品 B", "category", "indirect", "status", "open")),
                            new CardSeed("竞品 C 资料归档", "📁", Map.of("competitor", "竞品 C", "category", "potential", "status", "archived")))),

            new TemplateSeed("团队回顾", "周期性团队回顾：记录做得好的、待改进与行动项，跟进落实。",
                    "🧭", List.of(
                    prop("category", "分类", "select", Map.of(
                            "good", "做得好的", "improve", "待改进", "action", "行动项")),
                    prop("owner", "负责人", "member", null),
                    prop("priority", "优先级", "select", Map.of(
                            "high", "高", "medium", "中", "low", "低")),
                    prop("dueDate", "截止日期", "date", null)),
                    BOARD_VIEW_TITLE, "board", "category",
                    List.of(
                            new CardSeed("需求评审效率显著提升", "🙌", Map.of("category", "good")),
                            new CardSeed("联调阶段沟通不足", "⚠️", Map.of("category", "improve", "priority", "high")),
                            new CardSeed("建立每周同步例会", "📌", Map.of("category", "action", "priority", "medium")))));

    /** 构造属性定义（select 选项 id 取 key、value 取中文名，颜色对齐前端调色板） */
    private static Map<String, Object> prop(String id, String name, String type,
                                            Map<String, String> options) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("id", id);
        def.put("name", name);
        def.put("type", type);
        if (options != null) {
            List<Map<String, Object>> optionList = new ArrayList<>();
            int index = 0;
            String[] colors = {"#b1d1f6", "#ffd3c1", "#c7eac3", "#f7f0b6", "#e6d0ff", "#ffa9a9", "#ededed", "#f7ddc3"};
            for (Map.Entry<String, String> entry : options.entrySet()) {
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", entry.getKey());
                option.put("value", entry.getValue());
                option.put("color", colors[index % colors.length]);
                optionList.add(option);
                index++;
            }
            def.put("options", optionList);
        }
        return def;
    }
}
