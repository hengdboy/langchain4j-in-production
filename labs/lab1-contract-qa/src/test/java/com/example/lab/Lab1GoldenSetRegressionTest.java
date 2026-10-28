package com.example.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lab1 金标集回归测试：纯检索侧（本地 ONNX 嵌入模型，不需要 LLM、不需要 API key）。
 *
 * <p>这就是  说的"CI 阈值门禁"在 lab 里的样子：检索质量退化时，测试先红。
 * 阈值来自 2026-10-29 的实测基线（默认切块 450/80，MRR 0.66 附近）——
 * 你改了切块策略跑不过时，先看是退化了还是真的更好了，更好就改阈值并写明原因。
 */
@DisplayName("Lab1 合同检索金标集回归")
class Lab1GoldenSetRegressionTest {

    private static ContractKnowledge kb() {
        ContractKnowledge kb = new ContractKnowledge(450, 80);
        kb.ingestClasspathCorpus();
        return kb;
    }

    @Test
    @DisplayName("语料入库：两份合同，块数为正且都含关键条款")
    void corpusIngested() {
        ContractKnowledge kb = kb();
        assertThat(kb.storeSize()).isBetween(5, 40);
        // 抽查两个 needle 确实在库里（保证金标集与语料一致）
        assertThat(kb.search("押金退还", 10).stream()
                .anyMatch(s -> s.text().contains("13,600 元"))).isTrue();
        assertThat(kb.search("逾期交货违约金", 10).stream()
                .anyMatch(s -> s.text().contains("0.03%"))).isTrue();
    }

    @Test
    @DisplayName("金标集 10 题：recall@5 ≥ 0.8（2026-10-29 基线；改完切块策略用你的新数字替换它）")
    void recallAt5Baseline() {
        RetrievalEvaluator.Report report = RetrievalEvaluator.evaluate(
                kb(), RetrievalEvaluator.loadGoldenSet(), 5);
        assertThat(report.recallAt5()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    @DisplayName("金标集 10 题：MRR ≥ 0.55（基线门禁——检索退化时测试先红）")
    void mrrAboveGate() {
        RetrievalEvaluator.Report report = RetrievalEvaluator.evaluate(
                kb(), RetrievalEvaluator.loadGoldenSet(), 5);
        assertThat(report.mrr()).isGreaterThanOrEqualTo(0.55);
    }

    @Test
    @DisplayName("表格行离开表头就是碎块：450/80 下押金行应当能检回（分块策略敏感的例证）")
    void tableRowSurvivesDefaultChunking() {
        ContractKnowledge kb = kb();
        RetrievalEvaluator.Report report = RetrievalEvaluator.evaluate(
                kb, RetrievalEvaluator.loadGoldenSet(), 5);
        // 押金问题（needle 在正文段而非表格里）默认策略必须检回
        RetrievalEvaluator.CaseResult deposit = report.details().stream()
                .filter(c -> c.question().contains("押金"))
                .findFirst().orElseThrow();
        assertThat(deposit.rank()).isGreaterThan(0);
    }
}
