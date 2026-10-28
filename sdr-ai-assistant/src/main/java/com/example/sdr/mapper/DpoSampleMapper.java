package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.entity.DpoSample;
import org.apache.ibatis.annotations.Mapper;

/**
 * Ch24 v0.28 DPO 样本 Mapper。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Mapper
public interface DpoSampleMapper extends BaseMapper<DpoSample> {
}