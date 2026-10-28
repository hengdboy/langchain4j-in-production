package com.example.sdr.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.sdr.core.LeadContext;
import com.example.sdr.core.LeadStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/**
 * Ch24 SDR 销售线索 Mapper（MyBatis-Plus）。
 *
 * <p>继承 {@link BaseMapper} 自动获得 CRUD（insert / update / delete / selectById / selectList）。
 * 自定义方法用 {@code @Select} 注解（XML 也可，本项目为简洁用注解）。
 *
 * <h2>常用自定义方法</h2>
 * <ul>
 *   <li>{@link #findStep1SentBefore(Instant)} —— 找 STEP1_SENT 且 lastSentAt + 72h 已过的 lead</li>
 *   <li>{@link #findDormantBefore(Instant)} —— 找 DORMANT 且 90 天已过的 lead</li>
 *   <li>{@link #findByStatus(LeadStatus)} —— 找指定状态的所有 lead</li>
 * </ul>
 *
 * @author langchain4j-tutorial
 * @since v0.23
 */
@Mapper
public interface LeadMapper extends BaseMapper<LeadContext> {

    /**
     * 找 STEP1_SENT 状态且 lastSentAt 早于 threshold 的 lead（用于 72h 超时检测）。
     */
    @Select("SELECT * FROM `lead` WHERE status = 'STEP1_SENT' AND last_sent_at IS NOT NULL AND last_sent_at < #{threshold}")
    List<LeadContext> findStep1SentBefore(@Param("threshold") Instant threshold);

    /**
     * 找 DORMANT 状态且 updated_at 早于 threshold 的 lead（用于 90 天盘活检测）。
     */
    @Select("SELECT * FROM `lead` WHERE status = 'DORMANT' AND updated_at < #{threshold}")
    List<LeadContext> findDormantBefore(@Param("threshold") Instant threshold);

    /**
     * 找指定状态的所有 lead（按 id 倒序）。
     */
    @Select("SELECT * FROM `lead` WHERE status = #{status} ORDER BY id DESC")
    List<LeadContext> findByStatus(@Param("status") LeadStatus status);
}