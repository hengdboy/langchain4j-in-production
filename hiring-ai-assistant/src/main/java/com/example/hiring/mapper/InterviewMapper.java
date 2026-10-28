package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.Interview;
import org.apache.ibatis.annotations.Mapper;

/**  面试安排表 Mapper。 */
@Mapper
public interface InterviewMapper extends BaseMapper<Interview> {
}
