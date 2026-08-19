package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.InviteToken;
import org.apache.ibatis.annotations.Mapper;

/**
 * 注册邀请凭证 Mapper。
 */
@Mapper
public interface InviteTokenMapper extends BaseMapper<InviteToken> {
}
