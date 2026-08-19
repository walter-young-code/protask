package com.protask.dto;

import lombok.Data;
import org.springframework.util.StringUtils;

/**
 * 用户视图对象（脱敏，不含密码）。
 */
@Data
public class UserVO {

    private Long id;

    private String username;

    private String nickname;

    /** 名（First Name） */
    private String firstName;

    /** 姓（Last Name） */
    private String lastName;

    /** 全名：First + Last 拼接，未填写时为空 */
    private String fullName;

    private String email;

    private String avatar;

    /** 所属团队名称（可空） */
    private String teamName;

    private String role;

    /** 拼接姓名：两侧去空格，仅有一项时直接返回该项，均未填写返回 null */
    public static String fullNameOf(String firstName, String lastName) {
        boolean hasFirst = StringUtils.hasText(firstName);
        boolean hasLast = StringUtils.hasText(lastName);
        if (!hasFirst && !hasLast) {
            return null;
        }
        if (!hasFirst) {
            return lastName.trim();
        }
        if (!hasLast) {
            return firstName.trim();
        }
        return firstName.trim() + " " + lastName.trim();
    }
}
