package com.example.assistant.service;

import com.example.assistant.assistant.DocQaAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 新增：RAG 评估器（教学用"轻量评估框架"）
 *
 * <p>LangChain4j 1.19.0 核心库<b>没有</b>内置评估模块（已核实：core jar 无 evaluator 包），
 * 所以本项目自己用基础原语搭一套最小可跑的评估：
 * <ul>
 * <li>检索指标（离线、无需 API Key）：Recall@K、MRR、命中率 —— 直接跑 RetrievalAugmentor；</li>
 * <li>答案评估（需 LLM Key）：忠实度 Faithfulness、正确性 Correctness —— 用 LLM-as-Judge。</li>
 * </ul>
 *
 * <p>核心思路：用一份 <b>golden set</b>（问题 + 应出现在上下文里的关键句 + 标准答案）做回归基线，
 * 每次改了分块/检索/重排参数，跑一遍评估看指标涨跌，形成"评估→调参→再评估"的闭环。
 */
@Service
@Slf4j
public class RagEvaluator {

    private final RetrievalAugmentor retrievalAugmentor;
    private final ChatModel chatModel;
    private final DocQaAssistant docQaAssistant;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagEvaluator(RetrievalAugmentor retrievalAugmentor,
                        @Qualifier("openAiChatModel") ChatModel chatModel,
                        DocQaAssistant docQaAssistant) {
        this.retrievalAugmentor = retrievalAugmentor;
        this.chatModel = chatModel;
        this.docQaAssistant = docQaAssistant;
        log.info("[Eval] 使用 RetrievalAugmentor = {}", retrievalAugmentor.getClass().getName());
    }

    /** golden set 一条用例 */
    public record GoldenCase(String question, String expectedContextContains, String referenceAnswer) {}

    /** golden set 文件结构 */
    public record GoldenSet(List<GoldenCase> cases) {}

    /** 单条用例的检索结果片段 */
    public record RetrievedChunk(int rank, String text, Double score) {}

    /** 单条用例的评估结果 */
    public record CaseResult(String question, boolean hitAt1, boolean hitAt3, boolean hitAt5,
                             double mrr, List<RetrievedChunk> context,
                             String answer, Boolean faithful, Boolean correct) {}

    /** 整体评估报告 */
    public record EvaluationReport(double recallAt1, double recallAt3, double recallAt5, double mrr,
                                    int totalCases, int faithfulCount, int correctCount,
                                    boolean answerJudged, List<CaseResult> cases) {}

    /**
     * 运行评估。
     *
     * @param includeAnswer 是否包含"端到端答案 + LLM-as-Judge"（需要 API Key，会触发多次 LLM 调用）
     */
    public EvaluationReport evaluate(boolean includeAnswer) {
        GoldenSet goldenSet = loadGoldenSet();
        List<CaseResult> results = new ArrayList<>();
        int hit1 = 0, hit3 = 0, hit5 = 0;
        double mrrSum = 0.0;
        int faithfulCount = 0, correctCount = 0;

        for (GoldenCase c : goldenSet.cases()) {
            // ① 检索：直接跑 Ch12 装配好的 RetrievalAugmentor（向量+关键词→RRF 融合→重排）
            UserMessage um = UserMessage.from(c.question());
            AugmentationResult aug = retrievalAugmentor.augment(
                    new AugmentationRequest(um, Metadata.from(um, "eval", List.of())));
            List<Content> contents = aug.contents();

            // ② 取文本 + 分数，构造片段列表
            List<RetrievedChunk> chunks = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            int rank = 0;
            for (Content content : contents) {
                String text = content.textSegment().text();
                Double score = resolveScore(content);
                chunks.add(new RetrievedChunk(++rank, text, score));
                texts.add(text);
            }

            // ③ 检索指标：Recall@K + MRR
            String needle = c.expectedContextContains().toLowerCase();
            boolean h1 = containsAt(texts, needle, 1);
            boolean h3 = containsAt(texts, needle, 3);
            boolean h5 = containsAt(texts, needle, 5);
            double mrr = reciprocalRank(texts, needle);
            hit1 += h1 ? 1 : 0;
            hit3 += h3 ? 1 : 0;
            hit5 += h5 ? 1 : 0;
            mrrSum += mrr;

            // ④ 答案评估（可选）：端到端回答 + 忠实度 / 正确性 LLM-as-Judge
            String answer = null;
            Boolean faithful = null;
            Boolean correct = null;
            if (includeAnswer) {
                answer = safe(() -> docQaAssistant.ask(c.question()).content());
                String ctxText = String.join("\n", texts);
                faithful = judgeFaithful(ctxText, answer);
                correct = judgeCorrect(c.question(), c.referenceAnswer(), answer);
                if (Boolean.TRUE.equals(faithful)) faithfulCount++;
                if (Boolean.TRUE.equals(correct)) correctCount++;
            }

            results.add(new CaseResult(c.question(), h1, h3, h5, mrr, chunks, answer, faithful, correct));
            log.info("评估用例[{}] hit@1={} hit@3={} hit@5={} mrr={}",
                    c.question(), h1, h3, h5, String.format("%.3f", mrr));
        }

        int n = goldenSet.cases().size();
        return new EvaluationReport(
                (double) hit1 / n, (double) hit3 / n, (double) hit5 / n, mrrSum / n,
                n, faithfulCount, correctCount, includeAnswer, results);
    }

    // ===== 指标计算 =====

    /** topK 个片段的拼接文本中是否包含关键句（忽略大小写） */
    private boolean containsAt(List<String> texts, String needle, int k) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(k, texts.size()); i++) {
            sb.append(texts.get(i)).append("\n");
        }
        return sb.toString().toLowerCase().contains(needle);
    }

    /** 首个包含关键句的片段排名的倒数（MRR）；未命中返回 0 */
    private double reciprocalRank(List<String> texts, String needle) {
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).toLowerCase().contains(needle)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /** 取重排分（优先 RERANKED_SCORE，否则 SCORE） */
    private Double resolveScore(Content content) {
        Object v = content.metadata().get(ContentMetadata.RERANKED_SCORE);
        if (v instanceof Double d) return d;
        Object s = content.metadata().get(ContentMetadata.SCORE);
        return s instanceof Double d ? d : null;
    }

    // ===== LLM-as-Judge =====

    private Boolean judgeFaithful(String context, String answer) {
        String prompt = "你是严格的 RAG 质量评估员。下面有一段【参考资料】和一份【回答】。\n" +
                "请只判断：回答是否完全基于参考资料、没有编造或超出资料范围。\n" +
                "只回答一个字：基于资料就回“是”，有编造就回“否”。\n\n" +
                "【参考资料】\n" + context + "\n\n【回答】\n" + answer;
        return parseYesNo(askLlm(prompt));
    }

    private Boolean judgeCorrect(String question, String reference, String answer) {
        String prompt = "你是严格的问答质量评估员。\n" +
                "请判断【回答】是否正确回答了【问题】，且与【标准答案】要点一致（允许表述不同）。\n" +
                "只回答一个字：一致回“是”，不一致或错误回“否”。\n\n" +
                "【问题】" + question + "\n【标准答案】" + reference + "\n【回答】" + answer;
        return parseYesNo(askLlm(prompt));
    }

    private String askLlm(String prompt) {
        ChatResponse resp = chatModel.chat(UserMessage.from(prompt));
        AiMessage ai = resp.aiMessage();
        return ai.text() == null ? "" : ai.text();
    }

    /**
     * 极简判断：只认「是/否」打头（或全文就是这一个字）；格式不符返回 null（按未判定处理）。
     *
     * <p>修复记录：旧实现用 {@code contains("是")}，模型回一句「这是编造」
     * 也会命中「是」被判为忠实——判官明明在说回答有编造，解析器却记了一票通过。
     * 子串匹配对中文否定表达不可靠：「不是」里没有「否」，反而含「是」。
     */
    static Boolean parseYesNo(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        if (t.startsWith("是")) return true;
        if (t.startsWith("否") || t.startsWith("不是")) return false;
        return null;
    }

    // ===== 工具方法 =====

    private GoldenSet loadGoldenSet() {
        try {
            ClassPathResource res = new ClassPathResource("rag-golden-set.json");
            return objectMapper.readValue(res.getInputStream(), GoldenSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载 golden set 失败：rag-golden-set.json", e);
        }
    }

    /** 安全执行：评估不应因单条 LLM 调用异常而中断 */
    private String safe(java.util.function.Supplier<String> s) {
        try {
            return s.get();
        } catch (Exception e) {
            log.warn("评估调用异常：{}", e.getMessage());
            return "（评估调用异常：" + e.getMessage() + "）";
        }
    }
}
