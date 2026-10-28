package com.example.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lab1 检索评估器：纯检索侧的 recall@k 与 MRR，不需要 LLM。
 *
 * <p>判分规则：在前 k 个块里找第一个包含 needle 的块，其名次取倒数（第 1 名记 1.0，
 * 第 3 名记 1/3）；整个前 k 都没有记 0。needle 是原文里必须被检回的短句——
 * 取自语料原文，保证"答对"有客观定义。
 */
public final class RetrievalEvaluator {

    public record GoldenCase(String question, String needle, String doc) {}

    public record GoldenSet(List<GoldenCase> cases) {}

    public record CaseResult(String question, int rank, double reciprocalRank, String hitPreview) {}

    public record Report(int totalCases, double recallAt1, double recallAt3, double recallAt5,
                         double mrr, List<CaseResult> details) {}

    private RetrievalEvaluator() {}

    public static GoldenSet loadGoldenSet() {
        try (InputStream in = RetrievalEvaluator.class.getClassLoader()
                .getResourceAsStream("golden-set.json")) {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(in, GoldenSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("加载 golden-set.json 失败", e);
        }
    }

    public static Report evaluate(ContractKnowledge kb, GoldenSet goldenSet, int k) {
        List<CaseResult> details = new ArrayList<>();
        double mrrSum = 0;
        int hit1 = 0, hit3 = 0, hit5 = 0;
        for (GoldenCase c : goldenSet.cases()) {
            int rank = -1;
            String preview = null;
            List<TextSegment> hits = kb.search(c.question(), k);
            for (int i = 0; i < hits.size(); i++) {
                if (hits.get(i).text().contains(c.needle())) {
                    rank = i + 1;
                    preview = hits.get(i).text();
                    break;
                }
            }
            double rr = rank > 0 ? 1.0 / rank : 0.0;
            mrrSum += rr;
            if (rank == 1) hit1++;
            if (rank >= 1 && rank <= 3) hit3++;
            if (rank >= 1 && rank <= 5) hit5++;
            details.add(new CaseResult(c.question(), rank, rr, preview));
        }
        int n = goldenSet.cases().size();
        return new Report(n, (double) hit1 / n, (double) hit3 / n, (double) hit5 / n,
                mrrSum / n, details);
    }
}
