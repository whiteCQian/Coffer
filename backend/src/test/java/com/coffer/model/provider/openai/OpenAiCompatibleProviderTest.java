package com.coffer.model.provider.openai;

import com.coffer.model.provider.ChatProvider;
import com.coffer.model.provider.EmbeddingProvider;
import com.coffer.model.provider.VisionProvider;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleProviderTest {

    @Test
    void chatProviderDelegatesMessagesAndExposesMetadata() {
        OpenAiChatModel delegate = mock(OpenAiChatModel.class);
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from("答复"))
                .build();
        when(delegate.chat(any(ChatMessage[].class))).thenReturn(response);

        ChatProvider provider = new OpenAiCompatibleChatProvider(delegate, "DEEPSEEK", "deepseek-chat");

        assertThat(provider.chat(UserMessage.from("问题")).aiMessage().text()).isEqualTo("答复");
        assertThat(provider.providerId()).isEqualTo("DEEPSEEK");
        assertThat(provider.modelName()).isEqualTo("deepseek-chat");
        verify(delegate).chat(any(ChatMessage[].class));
    }

    @Test
    void visionProviderUsesTheSameOpenAiCompatibleChatProtocol() {
        OpenAiChatModel delegate = mock(OpenAiChatModel.class);

        VisionProvider provider = new OpenAiCompatibleVisionProvider(delegate, "QWEN_VL", "qwen-vl-max");

        assertThat(provider).isInstanceOf(ChatProvider.class);
        assertThat(provider.providerId()).isEqualTo("QWEN_VL");
        assertThat(provider.modelName()).isEqualTo("qwen-vl-max");
    }

    @Test
    void embeddingProviderDelegatesTextEmbeddingAndExposesMetadata() {
        EmbeddingModel delegate = mock(EmbeddingModel.class);
        Response<Embedding> response = Response.from(Embedding.from(new float[]{0.1f, 0.2f}));
        when(delegate.embed(anyString())).thenReturn(response);
        when(delegate.dimension()).thenReturn(2);

        EmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                delegate, "QWEN_VL", "text-embedding-v3");

        assertThat(provider.embed("文本")).isSameAs(response);
        assertThat(provider.dimension()).isEqualTo(2);
        assertThat(provider.providerId()).isEqualTo("QWEN_VL");
        assertThat(provider.modelName()).isEqualTo("text-embedding-v3");
        verify(delegate).embed("文本");
    }
}
