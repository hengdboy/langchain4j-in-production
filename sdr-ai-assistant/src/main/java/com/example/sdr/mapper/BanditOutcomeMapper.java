package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.entity.BanditOutcome;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 v0.28 Bandit 待结算队列 Mapper。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Mapper
public interface BanditOutcomeMapper extends BaseMapper<BanditOutcome> {
}