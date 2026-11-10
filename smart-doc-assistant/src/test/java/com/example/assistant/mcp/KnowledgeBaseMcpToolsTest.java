package com.example.assistant.mcp;

import dev.langchain4j.exception.ToolErrorVisibleToLlmException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Ch19：MCP 工具入参校验。
 *
 * <p>回归点：{@code requireQuestion} 曾经在空问题时返回空串继续执行，
 * 后续 {@code embeddingModel.embed("")} 照常跑，检索结果无意义还白烧一次调用。
 * 现在必须在进入检索前抛出可被 SDK 转成 {@code isError:true} 的异常。
 *
 * @author hengdboy
 */
@DisplayName("KnowledgeBaseMcpTools：空问题在检索前失败")
class KnowledgeBaseMcpToolsTest {

    @Test
    @DisplayName("searchKnowledgeBase(null) 抛可见错误，且不触发 embedding")
    void searchRejectsNullQuestionBeforeEmbedding() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        KnowledgeBaseMcpTools tools = new KnowledgeBaseMcpTools(embeddingModel, null, null);

        assertThatThrownBy(() -> tools.searchKnowledgeBase(null, 3, 0.0))
                .isInstanceOf(ToolErrorVisibleToLlmException.class)
                .hasMessageContaining("问题不能为空");

        verifyNoInteractions(embeddingModel);
    }

    @Test
    @DisplayName("askWithRag(空白串) 抛可见错误，且不触发 RAG 管线")
    void ragRejectsBlankQuestionBeforeAugmentation() {
        KnowledgeBaseMcpTools tools = new KnowledgeBaseMcpTools(null, null, null);

        assertThatThrownBy(() -> tools.askWithRag("   ", 5, 0.0))
                .isInstanceOf(ToolErrorVisibleToLlmException.class)
                .hasMessageContaining("问题不能为空");
    }
}
