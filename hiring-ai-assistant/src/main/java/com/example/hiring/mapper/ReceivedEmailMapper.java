package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.ReceivedEmail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户回信 Mapper（MyBatis-Plus）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Mapper
public interface ReceivedEmailMapper extends BaseMapper<ReceivedEmail> {
}