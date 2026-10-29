package com.sprout.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sprout.agent.entity.UploadedFileEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadedFileMapper extends BaseMapper<UploadedFileEntity> {
}
