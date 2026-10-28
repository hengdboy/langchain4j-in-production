package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.JobPosition;
import org.apache.ibatis.annotations.Mapper;

/**  职位表 Mapper。 */
@Mapper
public interface JobPositionMapper extends BaseMapper<JobPosition> {
}
