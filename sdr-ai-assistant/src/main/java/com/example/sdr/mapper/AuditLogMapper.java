package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * v0.29 Audit：audit_log 表 MyBatis-Plus Mapper。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}