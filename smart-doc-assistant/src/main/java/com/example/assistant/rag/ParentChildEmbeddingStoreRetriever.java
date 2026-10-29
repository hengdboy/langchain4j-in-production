package com.example.assistant.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ch12：父子分块检索器——包装 {@link EmbeddingStoreContentRetriever}。
 *
 * <p>向量库里存的是"子块"（小、语义聚焦、召回准），检索命中的也是子块；
 * 但喂给 LLM 的应该是语义完整的"父块"（大、上下文足）。本检索器把命中的子块按
 * {@code parentId} 展开为父块文本并去重，返回父块 {@link Content} 列表。
 *
 * <p>未启用父子分块（段上无 {@code parentId}/{@code parentText}）时自动退化为"原样返回"，
 * 因此扁平模式也能正常工作。
 * @author hengdboy
 */
public class ParentChildEmbeddingStoreRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever delegate;

    public ParentChildEmbeddingStoreRetriever(EmbeddingStoreContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        List<Content> childContents = delegate.retrieve(query);

        Set<String> seenParent = new LinkedHashSet<>();
        List<Content> parentContents = new ArrayList<>();
        for (Content child : childContents) {
            TextSegment seg = child.textSegment();
            String parentId = seg.metadata().getString("parentId");
            String parentText = seg.metadata().getString("parentText");
            // 有父块信息且与子块不同 → 展开为父块（按 parentId 去重）
            if (parentId != null && parentText != null && !parentText.equals(seg.text())) {
                if (seenParent.add(parentId)) {
                    parentContents.add(Content.from(TextSegment.from(parentText, seg.metadata())));
                }
            } else {
                // 扁平段：直接保留
                parentContents.add(child);
            }
        }
        return parentContents;
    }
}
