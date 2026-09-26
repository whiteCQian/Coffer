package com.coffer.model.provider.openai;

import com.coffer.model.provider.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

/** OpenAI 兼容 Embedding Provider 默认实现。 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingModel delegate;
    private final String providerId;
    private final String modelName;

    public OpenAiCompatibleEmbeddingProvider(EmbeddingModel delegate, String providerId, String modelName) {
        this.delegate = delegate;
        this.providerId = providerId;
        this.modelName = modelName;
    }

    @Override
    public Response<Embedding> embed(String text) {
        return com.coffer.model.provider.ModelInvocationException.safely(() -> delegate.embed(text));
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
    public int dimension() {
        return delegate.dimension();
    }
}
