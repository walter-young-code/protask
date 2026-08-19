-- =====================================================
-- V3: 任务属性全动态化
-- 1) 清除 card 块存量数据中的预制属性键 status/priority/assignee/dueDate
-- 2) 剥离项目属性模板中的四个内置定义（保留用户自定义属性）
-- =====================================================

-- 1. card 块：移除 fields.properties 中的预制属性键 --------------------------
UPDATE block
SET fields = JSON_REMOVE(fields,
                         '$.properties.status',
                         '$.properties.priority',
                         '$.properties.assignee',
                         '$.properties.dueDate')
WHERE type = 'card'
  AND fields IS NOT NULL;

-- 2. project：模板中过滤掉内置定义，剩余为空时置为空数组 -------------------
UPDATE project p
SET p.card_properties = (
    SELECT COALESCE(JSON_ARRAYAGG(jt.prop), JSON_ARRAY())
    FROM JSON_TABLE(
        p.card_properties,
        '$[*]' COLUMNS (
            prop JSON PATH '$',
            pid VARCHAR(100) PATH '$.id'
        )
    ) AS jt
    WHERE jt.pid NOT IN ('status', 'priority', 'assignee', 'dueDate')
)
WHERE p.card_properties IS NOT NULL;
