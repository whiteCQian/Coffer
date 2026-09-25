package com.coffer.model.provider.openai;

import com.coffer.model.provider.VisionProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** OpenAI 兼容多模态 Chat Provider 默认实现。 */
public final class OpenAiCompatibleVisionProvider extends OpenAiCompatibleChatProvider
        implements VisionProvider {

    public OpenAiCompatibleVisionProvider(OpenAiChatModel delegate, String providerId, String modelName) {
        super(delegate, providerId, modelName);
    }
}
