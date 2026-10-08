package com.coffer.model.runtime;

import com.coffer.model.provider.VisionProvider;

/** Mode-aware router for vision calls. */
public final class ModeAwareVisionProvider extends ModeAwareChatProvider implements VisionProvider {

    public ModeAwareVisionProvider(ModelRuntimeModeService modeService,
                                   ModelRuntimeProviderFactory providerFactory,
                                   ModelExecutionSnapshotService snapshots) {
        super(modeService, providerFactory, snapshots);
    }

    @Override
    protected com.coffer.model.provider.ChatProvider delegate() {
        var snapshot = ModelExecutionContext.require();
        snapshots.requireCurrent(snapshot);
        return providerFactory.vision(snapshot.mode());
    }
}
