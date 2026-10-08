package com.coffer.model.runtime;

import com.coffer.model.provider.EmbeddingProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.output.Response;

/** Mode-aware router for embedding calls. */
public final class ModeAwareEmbeddingProvider implements EmbeddingProvider {

    private final ModelRuntimeModeService modeService;
    private final ModelRuntimeProviderFactory providerFactory;
    private final ModelExecutionSnapshotService snapshots;

    public ModeAwareEmbeddingProvider(ModelRuntimeModeService modeService,
                                      ModelRuntimeProviderFactory providerFactory,
                                      ModelExecutionSnapshotService snapshots) {
        this.modeService = modeService;
        this.providerFactory = providerFactory;
        this.snapshots = snapshots;
    }

    private EmbeddingProvider delegate() {
        var snapshot = ModelExecutionContext.require();
        snapshots.requireCurrent(snapshot);
        return providerFactory.embedding(snapshot.mode());
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
