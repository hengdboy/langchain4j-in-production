package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.SentEmail;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 邮件发送历史 Mapper（MyBatis-Plus）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Mapper
public interface SentEmailMapper extends BaseMapper<SentEmail> {
}