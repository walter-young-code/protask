package com.protask.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PasswordStrengthValidator 单元测试：至少 8 位，需包含大小写字母、数字和特殊字符中的至少 3 类。
 */
class PasswordStrengthValidatorTest {

    @Test
    void rejectsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> PasswordStrengthValidator.validate(null));
        assertEquals("auth.password.tooWeak", ex.getMessageKey());
    }

    @Test
    void rejectsTooShort() {
        // 7 chars, 4 categories but too short
        assertThrows(BusinessException.class,
                () -> PasswordStrengthValidator.validate("Ab1!efg"));
    }

    @Test
    void rejectsOnlyLowercase() {
        // 1 category
        assertThrows(BusinessException.class,
                () -> PasswordStrengthValidator.validate("abcdefgh"));
    }

    @Test
    void rejectsLowercaseAndDigitsOnly() {
        // 2 categories
        assertThrows(BusinessException.class,
                () -> PasswordStrengthValidator.validate("abcdef12"));
    }

    @Test
    void acceptsLowercaseUppercaseDigits() {
        // 3 categories, 8 chars
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("Abcdef12"));
    }

    @Test
    void acceptsLowercaseUppercaseSpecial() {
        // 3 categories, 8 chars
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("Abcdef!@"));
    }

    @Test
    void acceptsAllFourCategories() {
        // 4 categories
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("Abcdef1!"));
    }

    @Test
    void acceptsLongerPassword() {
        assertDoesNotThrow(() -> PasswordStrengthValidator.validate("MyStr0ng!Pass"));
    }
}
