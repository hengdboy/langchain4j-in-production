package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.core.LeadEvent;
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
public interface LeadEventMapper extends BaseMapper<LeadEvent> {
}
