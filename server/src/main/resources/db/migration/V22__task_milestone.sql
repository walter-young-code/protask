-- 任务里程碑显式标记：0 普通任务（同日任务渲染为 1 天短条），1 里程碑（甘特菱形）
ALTER TABLE block ADD COLUMN milestone TINYINT(1) NOT NULL DEFAULT 0 COMMENT '里程碑标记:0普通任务,1里程碑' AFTER sort_order;