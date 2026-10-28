package com.example.assistant.rag;

import com.example.assistant.service.LexicalSegmentIndex;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ch12：混合检索的"关键词 / 全文"一路（简化 BM25）。
 *
 * <p>向量检索擅长语义召回，但精确关键词（型号、专有名词、数字）常常漏。这一路对
 * {@link LexicalSegmentIndex} 做轻量词重叠打分，与向量路互补，召回结果交由
 * {@code ReciprocalRankFuser} 融合。同样会把子块展开为父块文本。
 *
 * <p>⚠️ 这是<b>教学用</b>的简化实现：分词对中文做"字 unigram + 相邻 bigram"、对拉丁做单词；
 * 打分是 BM25 的简化版（IDF 用本机索引的文档频率近似）。生产应把这一路换成
 * Postgres tsvector + zhparser（中文分词专业、可下推到 SQL），见 Ch12 末。
 */
public class KeywordContentRetriever implements ContentRetriever {

    private static final Pattern LATIN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK = Pattern.compile("[一-鿿]");

    private final LexicalSegmentIndex index;
    private final int topK;

    public KeywordContentRetriever(LexicalSegmentIndex index, int topK) {
        this.index = index;
        this.topK = topK;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Set<String> queryTerms = new LinkedHashSet<>(tokenize(query.text()));
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        // 用本机索引预计算 DF 与平均长度（索引很小，教学可接受每次重算）
        List<List<String>> docs = new ArrayList<>();
        for (TextSegment seg : index.all()) {
            docs.add(tokenize(seg.text()));
        }
        int n = docs.size();
        double avgdl = docs.stream().mapToInt(List::size).average().orElse(0.0);
        Map<String, Integer> df = new HashMap<>();
        for (List<String> d : docs) {
            for (String tok : new LinkedHashSet<>(d)) {
                df.put(tok, df.getOrDefault(tok, 0) + 1);
            }
        }

        List<ScoredSegment> scored = new ArrayList<>();
        for (TextSegment seg : index.all()) {
            double score = bm25(tokenize(seg.text()), queryTerms, df, n, avgdl);
            if (score > 0) {
                scored.add(new ScoredSegment(seg, score));
            }
        }
        scored.sort(Comparator.<ScoredSegment>comparingDouble(s -> s.score).reversed());

        // 展开为父块（按 parentId 去重），截断到 topK
        Set<String> seenParent = new LinkedHashSet<>();
        List<Content> result = new ArrayList<>();
        for (ScoredSegment s : scored) {
            if (result.size() >= topK) {
                break;
            }
            TextSegment seg = s.segment;
            String parentId = seg.metadata().getString("parentId");
            String parentText = seg.metadata().getString("parentText");
            if (parentId != null && parentText != null && !parentText.equals(seg.text())) {
                if (seenParent.add(parentId)) {
                    result.add(Content.from(TextSegment.from(parentText, seg.metadata())));
                }
            } else {
                result.add(Content.from(seg));
            }
        }
        return result;
    }

    /** 简化 BM25：k=1.5, b=0.75；IDF 用本机 DF 近似，+1 防负无穷。 */
    private double bm25(List<String> docTokens, Set<String> queryTerms,
                         Map<String, Integer> df, int n, double avgdl) {
        if (docTokens.isEmpty()) {
            return 0.0;
        }
        Map<String, Integer> tf = new HashMap<>();
        for (String tok : docTokens) {
            tf.put(tok, tf.getOrDefault(tok, 0) + 1);
        }
        double score = 0.0;
        double k = 1.5, b = 0.75;
        for (String qt : queryTerms) {
            Integer f = tf.get(qt);
            if (f == null) {
                continue;
            }
            int docFreq = df.getOrDefault(qt, 0);
            double idf = Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1);
            score += idf * (f * (k + 1)) / (f + k * (1 - b + b * docTokens.size() / avgdl));
        }
        return score;
    }

    /** 分词：拉丁单词 + 中文"字 unigram + 相邻 bigram"。 */
    private List<String> tokenize(String text) {
        String t = text.toLowerCase();
        List<String> tokens = new ArrayList<>();
        Matcher m = LATIN.matcher(t);
        while (m.find()) {
            tokens.add("w:" + m.group());
        }
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            String ch = String.valueOf(t.charAt(i));
            if (CJK.matcher(ch).matches()) {
                cjk.append(ch);
            } else {
                emitCjkBigrams(cjk, tokens);
                cjk.setLength(0);
            }
        }
        emitCjkBigrams(cjk, tokens);
        return tokens;
    }

    private void emitCjkBigrams(StringBuilder cjk, List<String> tokens) {
        String s = cjk.toString();
        for (int i = 0; i < s.length(); i++) {
            tokens.add("c:" + s.charAt(i));
            if (i + 1 < s.length()) {
                tokens.add("c:" + s.charAt(i) + String.valueOf(s.charAt(i + 1)));
            }
        }
    }

    private record ScoredSegment(TextSegment segment, double score) {}
}
