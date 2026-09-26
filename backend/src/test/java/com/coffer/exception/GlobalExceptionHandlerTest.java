package com.coffer.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器回归测试：锁定「请求体解析失败」错误分类。
 *
 * <p>此前 Jackson 解析非 UTF-8 请求体抛出的 {@code JsonParseException}（{@code IOException} 子类）
 * 会经 {@link GlobalExceptionHandler#handleRuntime} 的 cause 链拆解被误判为 MinIO 网络错误（504），
 * 实际应为 400「请求体格式错误」。本测试用非法 UTF-8 字节体触发该场景，验证分类正确。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidUtf8BodyReturns400NotMinio504() throws Exception {
        // 0xbb 是非法的 UTF-8 起始字节，Jackson 解析请求体时必然抛 HttpMessageNotReadableException
        byte[] body = "{\"fileId\":1,\"tagId\":7,\"newTagName\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = {0x5c, 0x22, (byte) 0xbb, 0x22, 0x7d}; // \"<invalid>\"}
        byte[] full = new byte[body.length + invalid.length];
        System.arraycopy(body, 0, full, 0, body.length);
        System.arraycopy(invalid, 0, full, body.length, invalid.length);

        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(full))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求体格式错误，请检查 JSON 语法与字符编码"));
    }
}
