package com.example.hiring.evolution;

import com.example.hiring.entity.DpoSample;
import com.example.hiring.mapper.DpoSampleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Ch24 v0.28 DPO 样本写入器。
 *
 * <h2>source 常量</h2>
 * <ul>
 *   <li>{@link #SOURCE_HUMAN}：销售二元标注（高质量、慢）</li>
 *   <li>{@link #SOURCE_BANDIT}：Bandit 对比样本（中质量、自动）</li>
 *   <li>{@link #SOURCE_FEEDBACK}：人工纠偏（高质量、手动）</li>
 * </ul>
 *
 * <p>v0.28 教学版只启用 {@code SOURCE_BANDIT}：Bandit 对比同一 prompt 不同臂产出，
 * 转化高=chosen、低=rejected。{@code SOURCE_HUMAN} 留接口位。
 *
 * @author langchain4j-tutorial
 * @since v0.28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DpoSampleWriter {

    public static final String SOURCE_HUMAN = "human";
    public static final String SOURCE_BANDIT = "bandit";
    public static final String SOURCE_FEEDBACK = "feedback";

    private final DpoSampleMapper mapper;

    /**
     * 写入 Bandit 对比样本（chosen=当前臂产出，rejected=同 prompt 的默认臂 baseline）。
     */
    public DpoSample writeBanditSample(String prompt, String chosen, String rejected, String metadata) {
        return write(prompt, chosen, rejected, SOURCE_BANDIT, metadata);
    }

    public DpoSample write(String prompt, String chosen, String rejected,
                            String source, String metadata) {
        DpoSample s = new DpoSample();
        s.setPrompt(prompt);
        s.setChosen(chosen);
        s.setRejected(rejected);
        s.setSource(source);
        s.setMetadata(metadata);
        s.setUsedInTraining(false);
        s.setCreatedAt(Instant.now());
        mapper.insert(s);
        log.info("📚 [DPO] wrote sample id={} source={} chosenChars={} rejectedChars={}",
                s.getId(), source,
                chosen == null ? 0 : chosen.length(),
                rejected == null ? 0 : rejected.length());
        return s;
    }
}