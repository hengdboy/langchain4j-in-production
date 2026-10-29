package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.JobPosition;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 职位表 Mapper。
 * @author hengdboy
 */
@Mapper
public interface JobPositionMapper extends BaseMapper<JobPosition> {
}
