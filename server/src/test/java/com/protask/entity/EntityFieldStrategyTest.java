package com.protask.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 可清空字段的更新策略回归守护：业务上会被置 NULL 的列必须显式声明
 * ALWAYS/IGNORED 策略，否则 MyBatis-Plus 默认 NOT_NULL 策略跳过 null，
 * 造成「更新成功但未生效」的静默失败（如任务置根、注销解除团队、清空项目描述）。
 * Mockito 单测无法验证生成的 SQL，此测试以反射锁定注解防误删。
 */
class EntityFieldStrategyTest {

    /** 断言字段更新策略允许写 NULL（ALWAYS 或 IGNORED 均包含 null） */
    private void assertNullableUpdate(Class<?> entity, String fieldName) throws NoSuchFieldException {
        Field field = entity.getDeclaredField(fieldName);
        TableField annotation = field.getAnnotation(TableField.class);
        assertNotNull(annotation, entity.getSimpleName() + "." + fieldName + " 缺少 @TableField 策略声明");
        FieldStrategy strategy = annotation.updateStrategy();
        assertEquals(FieldStrategy.ALWAYS, strategy,
                entity.getSimpleName() + "." + fieldName + " 需 ALWAYS 策略才能写 NULL（当前 " + strategy + "）");
    }

    @Test
    void blockParentTaskIdAllowsNull() throws NoSuchFieldException {
        // 子任务设为根任务
        assertNullableUpdate(Block.class, "parentTaskId");
    }

    @Test
    void userTeamIdAllowsNull() throws NoSuchFieldException {
        // 注销用户解除团队归属 / 管理后台团队改「无」
        assertNullableUpdate(User.class, "teamId");
    }

    @Test
    void projectDescriptionAllowsNull() throws NoSuchFieldException {
        // 清空项目/模板描述（前端清空时传 null）
        assertNullableUpdate(Project.class, "description");
    }

    @Test
    void projectIconAllowsNull() throws NoSuchFieldException {
        // 模板链路全量回写清空图标
        assertNullableUpdate(Project.class, "icon");
    }

    @Test
    void projectShareCredentialsAllowNull() throws NoSuchFieldException {
        // 关闭分享/日历订阅（历史实现用 IGNORED，同样包含 null）
        for (String name : new String[]{"shareId", "calendarToken"}) {
            Field field = Project.class.getDeclaredField(name);
            TableField annotation = field.getAnnotation(TableField.class);
            assertNotNull(annotation, "Project." + name + " 缺少 @TableField 策略声明");
            FieldStrategy strategy = annotation.updateStrategy();
            assertEquals(FieldStrategy.IGNORED, strategy,
                    "Project." + name + " 需 IGNORED/ALWAYS 策略才能写 NULL（当前 " + strategy + "）");
        }
    }
}