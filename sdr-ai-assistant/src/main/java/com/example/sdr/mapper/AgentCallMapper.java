package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.entity.AgentCall;
import org.apache.ibatis.annotations.Mapper;

/**
 * v0.29 Observability：agent_call 表 MyBatis-Plus Mapper。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Mapper
public interface AgentCallMapper extends BaseMapper<AgentCall> {
}