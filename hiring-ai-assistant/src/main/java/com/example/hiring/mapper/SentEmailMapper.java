package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.SentEmail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 邮件发送历史 Mapper（MyBatis-Plus）。
 *
 * @author hengdboy
 * @since v0.26
 */
@Mapper
public interface SentEmailMapper extends BaseMapper<SentEmail> {
}