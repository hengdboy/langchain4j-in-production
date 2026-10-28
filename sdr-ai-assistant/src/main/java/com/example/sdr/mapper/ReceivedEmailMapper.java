package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.entity.ReceivedEmail;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 客户回信 Mapper（MyBatis-Plus）。
 *
 * @author langchain4j-tutorial
 * @since v0.26
 */
@Mapper
public interface ReceivedEmailMapper extends BaseMapper<ReceivedEmail> {
}