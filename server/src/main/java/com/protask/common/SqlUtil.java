package com.protask.common;

/**
 * SQL 工具：LIKE 模糊查询关键词转义。
 */
public final class SqlUtil {

    private SqlUtil() {
    }

    /**
     * 转义 LIKE 通配符（% _ \），防止用户输入通配符扩大匹配范围。
     * MySQL 默认以反斜杠为 LIKE 转义符。
     */
    public static String escapeLike(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
