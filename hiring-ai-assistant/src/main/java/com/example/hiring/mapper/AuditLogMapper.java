package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * v0.29 Audit：audit_log 表 MyBatis-Plus Mapper。
 *
 * @author hengdboy
 * @since v0.29
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}