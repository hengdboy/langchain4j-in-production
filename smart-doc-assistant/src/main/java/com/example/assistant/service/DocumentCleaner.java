package com.example.assistant.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentTransformer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Ch10：文档清洗器（DocumentTransformer 实现）。
 *
 * <p>文档解析出来是"原始文本"，往往带各种噪声：BOM、零宽字符、\r\n、
 * 制表符、多余空白、分隔线等。清洗器在分块之前做规范化，让切出来的块更干净。
 *
 * <p>核心原则：清洗要"保守"——只去除确定是噪声的内容，
 * 不要误删正文语义（比如代码缩进、表格对齐空格）。
 */
@Component
public class DocumentCleaner implements DocumentTransformer {

    /** UTF-8 BOM */
    private static final Pattern BOM = Pattern.compile("^\uFEFF");

    /** 零宽字符（\u200B 零宽空格 / \u200C 零宽非连接符 / \u200D 零宽连接符 / \uFEFF） */
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B\\u200C\\u200D\\uFEFF]");

    /** 纯分隔线行：------ 或 ==== 或 **** 等，长度 >= 5 */
    private static final Pattern SEPARATOR_LINE = Pattern.compile("^[\\-=\\*_\\s]{5,}$");

    /** 连续 3 个以上空白（含换行）压缩为单个换行 */
    private static final Pattern MULTI_BLANK = Pattern.compile("[ \\t]{3,}");

    /** 连续 2 个以上空行压缩为 1 个 */
    private static final Pattern MULTI_EMPTY_LINE = Pattern.compile("\\n{3,}");

    @Override
    public Document transform(Document document) {
        String text = document.text();

        text = BOM.matcher(text).replaceFirst("");
        text = ZERO_WIDTH.matcher(text).replaceAll("");

        text = text.replace("\r\n", "\n").replace('\r', '\n');

        text = MULTI_BLANK.matcher(text).replaceAll(" ");

        // Markdown 的 --- 分隔行不是正文，按整行匹配过滤
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!SEPARATOR_LINE.matcher(line).matches()) {
                sb.append(line).append('\n');
            }
        }

        String cleaned = MULTI_EMPTY_LINE.matcher(sb.toString()).replaceAll("\n\n").trim();

        return Document.from(cleaned, document.metadata().copy());
    }

    @Override
    public List<Document> transformAll(List<Document> documents) {
        return DocumentTransformer.super.transformAll(documents);
    }
}
