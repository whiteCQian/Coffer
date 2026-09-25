package com.coffer.model.runtime;

import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.provider.ChatProvider;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/** Routes every call through an immutable task mode snapshot when one exists. */
public class ModeAwareChatProvider implements ChatProvider {

    protected final ModelRuntimeModeService modeService;
    protected final ModelRuntimeProviderFactory providerFactory;

    public ModeAwareChatProvider(ModelRuntimeModeService modeService,
                                 ModelRuntimeProviderFactory providerFactory) {
        this.modeService = modeService;
        this.providerFactory = providerFactory;
    }

    protected ChatProvider delegate() {
        GovernanceRunMode mode = modeService.providerMode();
        return providerFactory.chat(mode);
    }

    @Override
    public String providerId() {
        return delegate().providerId();
    }

    @Override
    public String modelName() {
        return delegate().modelName();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate().chat(request);
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return delegate().chat(request, options);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return delegate().doChat(request);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate().defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate().listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate().provider();
    }

    @Override
    public String chat(String userMessage) {
        return delegate().chat(userMessage);
    }

    @Override
    public ChatResponse chat(ChatMessage... messages) {
        return delegate().chat(messages);
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages) {
        return delegate().chat(messages);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate().supportedCapabilities();
    }
}
