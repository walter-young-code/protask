package com.protask.common;

/**
 * 密码强度校验工具类：至少 8 位，需包含大小写字母、数字和特殊字符中的至少 3 类。
 */
public final class PasswordStrengthValidator {

    private PasswordStrengthValidator() {
    }

    /**
     * 校验密码强度，不满足时抛出 BusinessException。
     */
    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.password.tooWeak");
        }
        int categories = 0;
        if (password.chars().anyMatch(Character::isUpperCase)) categories++;
        if (password.chars().anyMatch(Character::isLowerCase)) categories++;
        if (password.chars().anyMatch(Character::isDigit)) categories++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) categories++;
        if (categories < 3) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.password.tooWeak");
        }
    }
}
