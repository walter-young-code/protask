package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.SiteSettingsRequest;
import com.protask.dto.SiteSettingsVO;
import com.protask.entity.SiteSetting;
import com.protask.mapper.SiteSettingMapper;
import com.protask.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 站点设置服务：品牌信息（应用名称/LOGO）的读取与更新。
 * 读取为公开接口（登录页需要），更新仅系统管理员。
 */
@Service
@RequiredArgsConstructor
public class SiteSettingService {

    private static final String KEY_SITE_NAME = "site_name";
    private static final String KEY_SITE_LOGO = "site_logo";
    private static final String DEFAULT_SITE_NAME = "ProTask";
    private static final int MAX_NAME_LENGTH = 50;

    /** LOGO 仅允许本站上传的图片地址（UUID 命名），拒绝外部 URL 与 SVG 脚本注入 */
    private static final Pattern LOGO_PATTERN = Pattern.compile(
            "/api/v1/upload/files/[0-9a-f]{32}\\.(png|jpg|jpeg|gif|webp|bmp)");

    private final SiteSettingMapper siteSettingMapper;
    private final PermissionService permissionService;

    /** 读取公开设置：缺失的键回退默认值，LOGO 未配置返回 null */
    public SiteSettingsVO getPublicSettings() {
        Map<String, String> values = loadAll();
        SiteSettingsVO vo = new SiteSettingsVO();
        vo.setName(StringUtils.hasText(values.get(KEY_SITE_NAME))
                ? values.get(KEY_SITE_NAME) : DEFAULT_SITE_NAME);
        String logo = values.get(KEY_SITE_LOGO);
        vo.setLogo(StringUtils.hasText(logo) ? logo : null);
        return vo;
    }

    /** 更新站点设置：仅系统管理员，名称必填且限长，LOGO 必须是本站上传图片地址 */
    public SiteSettingsVO updateSettings(Long operatorId, SiteSettingsRequest request) {
        if (!permissionService.isSystemAdmin(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin.permissionDenied");
        }
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "site.nameRequired");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "site.nameTooLong", MAX_NAME_LENGTH);
        }
        String logo = request.getLogo() == null ? "" : request.getLogo().trim();
        if (!logo.isEmpty() && !LOGO_PATTERN.matcher(logo).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "site.logoInvalid");
        }
        upsert(KEY_SITE_NAME, name);
        upsert(KEY_SITE_LOGO, logo);
        return getPublicSettings();
    }

    private void upsert(String key, String value) {
        SiteSetting existing = siteSettingMapper.selectById(key);
        if (existing == null) {
            SiteSetting record = new SiteSetting();
            record.setSettingKey(key);
            record.setSettingValue(value);
            siteSettingMapper.insert(record);
        } else {
            existing.setSettingValue(value);
            siteSettingMapper.updateById(existing);
        }
    }

    private Map<String, String> loadAll() {
        return siteSettingMapper.selectList(null).stream()
                .collect(Collectors.toMap(SiteSetting::getSettingKey,
                        SiteSetting::getSettingValue, (a, b) -> a));
    }
}
