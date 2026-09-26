package com.coffer.model.runtime;

import com.coffer.model.provider.VisionProvider;

/** Mode-aware router for vision calls. */
public final class ModeAwareVisionProvider extends ModeAwareChatProvider implements VisionProvider {

    public ModeAwareVisionProvider(ModelRuntimeModeService modeService,
                                   ModelRuntimeProviderFactory providerFactory) {
        super(modeService, providerFactory);
    }

    @Override
    protected com.coffer.model.provider.ChatProvider delegate() {
        return providerFactory.vision(ModelExecutionContext.require().mode());
    }
}
