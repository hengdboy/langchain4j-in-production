package com.example.hiring.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.hiring.entity.BanditState;
import org.apache.ibatis.annotations.Mapper;

/**
 * v0.28 Bandit 状态 Mapper。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Mapper
public interface BanditStateMapper extends BaseMapper<BanditState> {
}