package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
