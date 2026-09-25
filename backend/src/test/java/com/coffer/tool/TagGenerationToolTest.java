package com.coffer.tool;

import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.file.domain.CategoryType;
import com.coffer.model.provider.ChatProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TagGenerationTool} 结构化分类 + 标签解析测试：
 * 合法 JSON / Markdown 围栏剥离 / 纯文本与非法 JSON 降级 OTHER / 空白文本不调模型 /
 * {@code generate_tags} 工具路径语义不受影响。
 */
@SpringBootTest
class TagGenerationToolTest {

    @Autowired
    private TagGenerationTool tagGenerationTool;

    @MockitoBean
    private ChatProvider chatProvider;

    private void mockReply(String text) {
        when(chatProvider.chat(any(ChatMessage[].class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    }

    @Test
    void parsesValidJsonCategoryAndTags() {
        mockReply("{\"category\":\"合同\",\"tags\":[\"合同\",\"销售\",\"2025\"]}");

        TagAndCategoryResult result = tagGenerationTool.generateTagAndCategory("合同内容文本");

        assertThat(result.category()).isEqualTo(CategoryType.CONTRACT);
        assertThat(result.tags()).containsExactly("合同", "销售", "2025");
    }

    @Test
    void stripsJsonCodeFence() {
        mockReply("```json\n{\"category\":\"发票\",\"tags\":[\"发票\",\"财务\"]}\n```");

        TagAndCategoryResult result = tagGenerationTool.generateTagAndCategory("发票文本");

        assertThat(result.category()).isEqualTo(CategoryType.INVOICE);
        assertThat(result.tags()).containsExactly("发票", "财务");
    }

    @Test
    void plainCommaListFallsBackToOtherWithTags() {
        mockReply("合同,销售,2025");

        TagAndCategoryResult result = tagGenerationTool.generateTagAndCategory("文本");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).containsExactly("合同", "销售", "2025");
    }

    @Test
    void invalidJsonFallsBackToOther() {
        // 截断的非法 JSON：含冒号键值对残留，兜底过滤后无有效标签
        mockReply("{\"category\": \"乱写\"");

        TagAndCategoryResult result = tagGenerationTool.generateTagAndCategory("文本");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void blankTextReturnsOtherWithoutModelCall() {
        TagAndCategoryResult result = tagGenerationTool.generateTagAndCategory("   ");

        assertThat(result.category()).isEqualTo(CategoryType.OTHER);
        assertThat(result.tags()).isEmpty();
        verify(chatProvider, never()).chat(any(ChatMessage[].class));
    }

    @Test
    void generateTagsToolSemanticsUnchanged() {
        mockReply("智能存储,Agent,大模型,Spring Boot");

        String result = tagGenerationTool.generateTags("文本");

        assertThat(result).isEqualTo("智能存储，Agent，大模型，Spring Boot");
    }
}
