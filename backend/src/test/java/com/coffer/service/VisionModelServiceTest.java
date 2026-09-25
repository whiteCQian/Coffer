package com.coffer.service;

import com.coffer.dto.VisionResult;
import com.coffer.file.domain.CategoryType;
import com.coffer.model.provider.VisionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VisionModelService} 单元测试（纯 Mockito，不启 Spring）：
 * 注入 mock {@link VisionProvider}，避免真实网络调用。
 * 覆盖 parseVisionResult 容错（合法 JSON / Markdown 围栏 / 纯文本与非法 JSON 降级 OTHER）与
 * describeImage 发送消息内容（含 ImageContent 的 base64 + mime 与 TextContent）。
 */
class VisionModelServiceTest {

    private VisionProvider visionProvider;
    private VisionModelService service;

    @BeforeEach
    void setUp() {
        visionProvider = mock(VisionProvider.class);
        service = new VisionModelService(new ObjectMapper(), visionProvider);
    }

    private void mockReply(String text) {
        when(visionProvider.chat(any(ChatMessage[].class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }

    @Test
    void parsesValidJsonCategoryTagsAndDescription() {
        VisionResult result = service.parseVisionResult(
                "{\"category\":\"合同\",\"tags\":[\"合同\",\"扫描件\"],\"description\":\"一份销售合同\"}");

        assertThat(result.category()).isEqualTo(CategoryType.CONTRACT);
        assertThat(result.tags()).containsExactly("合同", "扫描件");
        assertThat(result.description()).isEqualTo("一份销售合同");
    }

    @Test
    void stripsJsonCodeFence() {
        VisionResult result = service.parseVisionResult(
                "```json\n{\"category\":\"发票\",\"tags\":[\"发票\",\"财务\"],\"description\":\"一张增值税发票\"}\n```");

        assertThat(result.category()).isEqualTo(CategoryType.INVOICE);
        assertThat(result.tags()).containsExactly("发票", "财务");
        assertThat(result.description()).isEqualTo("一张增值税发票");
    }

    @Test
    void plainTextFallsBackToOtherEmptyTagsAndDescription() {
        VisionResult result = service.parseVisionResult("这是一张照片");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).isEmpty();
        assertThat(result.description()).isEmpty();
    }

    @Test
    void invalidJsonFallsBackToOther() {
        // 截断的非法 JSON
        VisionResult result = service.parseVisionResult("{\"category\":");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).isEmpty();
        assertThat(result.description()).isEmpty();
    }

    @Test
    void blankReplyFallsBackToOther() {
        VisionResult result = service.parseVisionResult("   ");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).isEmpty();
        assertThat(result.description()).isEmpty();
    }

    @Test
    void describeImageSendsImageContentWithBase64AndMime() {
        mockReply("{\"category\":\"图片\",\"tags\":[\"风景\",\"天空\"],\"description\":\"蓝天白云\"}");

        VisionResult result = service.describeImage("QUJD", "image/jpeg", "photo.jpg");

        ArgumentCaptor<ChatMessage[]> captor = ArgumentCaptor.forClass(ChatMessage[].class);
        verify(visionProvider).chat(captor.capture());
        ChatMessage[] messages = captor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages[0]).isInstanceOf(SystemMessage.class);
        assertThat(messages[1]).isInstanceOf(UserMessage.class);

        UserMessage userMessage = (UserMessage) messages[1];
        // 第一个内容应为图片（base64 + mime 原样传入），第二个为文本提示
        assertThat(userMessage.contents()).hasSize(2);
        assertThat(userMessage.contents().get(0))
                .isInstanceOfSatisfying(ImageContent.class,
                        ic -> {
                            assertThat(ic.image().base64Data()).isEqualTo("QUJD");
                            assertThat(ic.image().mimeType()).isEqualTo("image/jpeg");
                        });
        assertThat(userMessage.contents().get(1)).isInstanceOf(TextContent.class);

        assertThat(result.category()).isEqualTo(CategoryType.IMAGE);
        assertThat(result.tags()).containsExactly("风景", "天空");
        assertThat(result.description()).isEqualTo("蓝天白云");
    }
}
