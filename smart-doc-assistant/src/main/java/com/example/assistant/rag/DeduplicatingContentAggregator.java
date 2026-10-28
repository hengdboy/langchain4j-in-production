package com.example.assistant.rag;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 去重装饰器：在真正聚合（融合 + 重排）之前，先按文本内容去掉跨 retriever 的重复 Content。
 *
 * <p>问题背景：{@code QueryRouter} 把同一 query 同时发给「向量路」与「关键词路」，
 * 两路各自内部已去重，但合并后同一父块可能同时出现两次（rank 紧挨着占坑）。
 * langchain4j 的 {@code ReRankingContentAggregator} 只排序不去重，所以包一层本装饰器。
 *
 * <p>按文本去重（而非 parentId），因为扁平段没有 parentId；用 {@link LinkedHashMap}
 * 保持每个 query 首次出现的顺序，避免改变原始排名语义。
 */
public class DeduplicatingContentAggregator implements ContentAggregator {

    private final ContentAggregator delegate;

    public DeduplicatingContentAggregator(ContentAggregator delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> aggregate(Map<Query, Collection<List<Content>>> queryToContents) {
        Map<Query, Collection<List<Content>>> deduped = new LinkedHashMap<>();
        for (Map.Entry<Query, Collection<List<Content>>> entry : queryToContents.entrySet()) {
            Set<String> seen = new LinkedHashSet<>();
            List<Content> merged = new ArrayList<>();
            for (List<Content> contents : entry.getValue()) {
                for (Content c : contents) {
                    String text = c.textSegment() != null ? c.textSegment().text() : "";
                    if (seen.add(text)) {
                        merged.add(c);
                    }
                }
            }
            deduped.put(entry.getKey(), List.of(merged));
        }
        return delegate.aggregate(deduped);
    }
}
