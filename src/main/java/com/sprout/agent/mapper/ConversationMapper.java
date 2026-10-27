package com.sprout.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sprout.agent.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
