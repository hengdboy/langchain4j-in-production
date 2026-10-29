package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.core.CandidateEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 候选人状态变更流水 Mapper（MyBatis-Plus）。
 *
 * <p>继承 {@link BaseMapper} 自动获得 CRUD；lead_event 表（ARCHITECTURE.md ）。
 *
 * @author hengdboy
 * @since v0.25
 */
@Mapper
public interface CandidateEventMapper extends BaseMapper<CandidateEvent> {
}
