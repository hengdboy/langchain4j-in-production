package com.example.hiring.evolution;

import com.example.hiring.entity.DpoSample;
import com.example.hiring.mapper.DpoSampleMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ch24 DpoSampleWriter 集成测试（需要 MySQL）。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Slf4j
@SpringBootTest
@DisplayName("DpoSampleWriter 集成测试")
class DpoSampleWriterTest {

    @Autowired DpoSampleWriter writer;
    @Autowired DpoSampleMapper mapper;

    @BeforeEach
    void clean() {
        mapper.delete(null);
    }

    @Test
    @DisplayName("【Bandit source】writeBanditSample 写入 dpo_sample 表")
    void testBanditWrite() {
        DpoSample s = writer.writeBanditSample(
                "prompt for ACME",
                "chosen content: formal quote",
                "",
                "{\"arm_id\":\"formal:quote:email\"}");
        assertThat(s.getId()).isNotNull();
        assertThat(s.getSource()).isEqualTo("bandit");
        assertThat(s.getUsedInTraining()).isFalse();
        assertThat(mapper.selectCount(null)).isEqualTo(1);
        log.info("✅ Bandit 样本写入：id={}", s.getId());
    }

    @Test
    @DisplayName("【Human source】write(prompt, chosen, rejected, human) 走人工标注源")
    void testHumanWrite() {
        DpoSample s = writer.write("p", "人类标注的好回复", "人类标注的差回复",
                DpoSampleWriter.SOURCE_HUMAN, null);
        assertThat(s.getSource()).isEqualTo("human");
        log.info("✅ Human 样本写入：id={}", s.getId());
    }
}