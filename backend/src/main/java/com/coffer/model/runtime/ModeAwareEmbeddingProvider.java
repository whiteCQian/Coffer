package com.coffer.model.runtime;

import com.coffer.model.provider.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;

/** Mode-aware router for embedding calls. */
public final class ModeAwareEmbeddingProvider implements EmbeddingProvider {

    private final ModelRuntimeModeService modeService;
    private final ModelRuntimeProviderFactory providerFactory;

    public ModeAwareEmbeddingProvider(ModelRuntimeModeService modeService,
                                      ModelRuntimeProviderFactory providerFactory) {
        this.modeService = modeService;
        this.providerFactory = providerFactory;
    }

    private EmbeddingProvider delegate() {
        return providerFactory.embedding(ModelExecutionContext.require().mode());
    }

    @Override
    public Response<Embedding> embed(String text) {
        return delegate().embed(text);
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
    public int dimension() {
        return delegate().dimension();
    }
}
