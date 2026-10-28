package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.core.CandidateEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 SDR 状态变更流水 Mapper（MyBatis-Plus）。
 *
 * <p>继承 {@link BaseMapper} 自动获得 CRUD；lead_event 表（ARCHITECTURE.md §4.3）。
 *
 * @author langchain4j-tutorial
 * @since v0.25
 */
@Mapper
public interface CandidateEventMapper extends BaseMapper<CandidateEvent> {
}
