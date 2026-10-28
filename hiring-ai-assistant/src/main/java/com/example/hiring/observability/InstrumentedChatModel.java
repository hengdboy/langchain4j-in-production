package com.example.hiring.observability;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.ModelProvider;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.29 Observability：包一层 ChatModel，注入 ChatModelListener。
 *
 * <p>LangChain4j 1.19 的 ChatModel 接口默认 {@code listeners()} 返回空列表，
 * 没有 setter。要加 listener 必须自写 wrapper，委托给底层 ChatModel 并在调用前后分发事件。
 *
 * @author langchain4j-tutorial
 * @since v0.29
 */
@RequiredArgsConstructor
public class InstrumentedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final List<ChatModelListener> listeners;

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        Map<Object, Object> attrs = new HashMap<>();
        dev.langchain4j.model.ModelProvider provider = delegate.provider();
        ChatModelRequestContext reqCtx = new ChatModelRequestContext(chatRequest, provider, attrs);
        // 分发 onRequest
        for (ChatModelListener l : listeners) {
            try {
                l.onRequest(reqCtx);
            } catch (Exception ignore) {
                // listener 自身错不应影响主流程
            }
        }
        try {
            ChatResponse resp = delegate.chat(chatRequest);
            ChatModelResponseContext respCtx = new ChatModelResponseContext(resp, chatRequest, provider, attrs);
            for (ChatModelListener l : listeners) {
                try {
                    l.onResponse(respCtx);
                } catch (Exception ignore) {
                }
            }
            return resp;
        } catch (RuntimeException ex) {
            ChatModelErrorContext errCtx = new ChatModelErrorContext(ex, chatRequest, provider, attrs);
            for (ChatModelListener l : listeners) {
                try {
                    l.onError(errCtx);
                } catch (Exception ignore) {
                }
            }
            throw ex;
        }
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest, dev.langchain4j.model.chat.ChatRequestOptions options) {
        return delegate.chat(chatRequest, options);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        List<ChatModelListener> all = new ArrayList<>(delegate.listeners());
        all.addAll(listeners);
        return all;
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<dev.langchain4j.model.chat.Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}