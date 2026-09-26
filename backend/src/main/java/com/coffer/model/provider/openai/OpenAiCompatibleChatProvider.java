package com.coffer.model.provider.openai;

import com.coffer.model.provider.ChatProvider;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容 Chat Provider 默认实现。
 *
 * <p>DeepSeek、Qwen 以及用户自建的 OpenAI 兼容端点都可以复用该适配器；
 * 业务层只看到 {@link ChatProvider}。</p>
 */
public class OpenAiCompatibleChatProvider implements ChatProvider {

    private final OpenAiChatModel delegate;
    private final String providerId;
    private final String modelName;

    public OpenAiCompatibleChatProvider(OpenAiChatModel delegate, String providerId, String modelName) {
        this.delegate = delegate;
        this.providerId = providerId;
        this.modelName = modelName;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.chat(request));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.chat(request, options));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.doChat(request));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public String chat(String userMessage) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.chat(userMessage));
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.chat(messages));
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.chat(messages));
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }
}
