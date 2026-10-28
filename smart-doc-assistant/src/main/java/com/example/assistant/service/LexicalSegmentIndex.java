package com.example.assistant.service;

import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ch12：进程内段索引，供「关键词检索器」（混合检索的"全文"一路）扫描。
 *
 * <p>向量库擅长语义召回，但不擅长精确关键词匹配（如型号、专有名词、数字）。
 * 混合检索的"全文"一路需要一个可扫描的段集合——本索引在入库时由
 * {@link com.example.assistant.service.DocumentIngestionService} 填充，
 * 启动时随建库重建。生产环境可把这路换成 Postgres tsvector + zhparser（见 Ch12 末）。
 */
@Component
public class LexicalSegmentIndex {

    private final List<TextSegment> segments = new CopyOnWriteArrayList<>();

    public void addAll(List<TextSegment> segments) {
        this.segments.addAll(segments);
    }

    public void clear() {
        this.segments.clear();
    }

    public List<TextSegment> all() {
        return List.copyOf(segments);
    }

    public int size() {
        return segments.size();
    }
}
