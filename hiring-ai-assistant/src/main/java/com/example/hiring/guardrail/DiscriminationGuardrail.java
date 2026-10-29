package com.example.hiring.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 歧义特征输出护栏：评估/评价的<b>产出文本</b>里出现受保护特征词即拦截。
 *
 * <h2>与 {@link DecisionFieldFilter} 的分工</h2>
 * <ul>
 * <li>Filter 管<b>输入</b>：受保护字段进不了 prompt（白名单，确定性）</li>
 * <li>Guardrail 管<b>输出</b>：就算模型从项目描述的字缝里读出了年龄线索并写进
 * 理由（"1990 年出生""已婚已育更适合该岗位"），落库前最后一道闸拦下</li>
 * </ul>
 *
 * <h2>策略：fatal 不 retry</h2>
 * <p>返回 {@code fatal(...)} 后 pipeline <b>不自动重试、不自动决策</b>——
 * 评价停在原地等人工修订。理由：合规违规场景下自动重试等于让模型再赌一次，
 * 停下来交给人才是正确姿势。
 *
 * @author hengdboy
 * @since v0.30
 */
@Slf4j
@Component
public class DiscriminationGuardrail implements OutputGuardrail {

    /**
     * 受保护特征词表。教学版用显式词表（可解释、可测试）；
     * 生产可升级为分类器，但词表永远是兜底——分类器会漏，词表不会。
     */
    static final List<String> PROTECTED_TERMS = List.of(
            "年龄", "岁", "性别", "女性", "男性", "女", "男",
            "已婚", "未婚", "生育", "婚育", "怀孕",
            "籍贯", "户籍", "民族", "政治面貌", "宗教",
            "年出生", "年出生");

    @Override
    public OutputGuardrailResult validate(AiMessage responseFromLLM) {
        String text = responseFromLLM.text();
        if (text == null || text.isBlank()) {
            return success();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String term : PROTECTED_TERMS) {
            if (lower.contains(term.toLowerCase(Locale.ROOT))) {
                log.warn("[DiscriminationGuardrail] blocked protected term: '{}'", term);
                return fatal("产出文本包含受保护特征词「" + term + "」，请人工修订");
            }
        }
        return success();
    }
}
