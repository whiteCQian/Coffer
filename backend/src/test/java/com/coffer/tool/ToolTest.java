package com.coffer.tool;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.model.provider.ChatProvider;
import com.coffer.service.MinioStorageService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.minio.ObjectWriteResponse;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 工具类集成测试：单独调用每个 Tool 并打印结果。
 *
 * <p>外部依赖处理：文件解析走真实 MinIO（本机已启动，真实上传→读取）；文件搜索走真实 H2 数据库；
 * 标签生成调用 {@link OpenAiChatModel} 用 {@link MockitoBean} 模拟固定响应（真实 DeepSeek 调用留到启动实测阶段）。
 */
@SpringBootTest
@ExtendWith(SpringExtension.class)
class ToolTest {

    @Autowired
    private FileParsingTool fileParsingTool;
    @Autowired
    private TagGenerationTool tagGenerationTool;
    @Autowired
    private FileSearchTool fileSearchTool;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private FileMetadataRepository fileMetadataRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;
    /** 真实 MinIO 客户端：本机 MinIO 已启动，文件解析走真实上传→读取链路。 */
    @Autowired
    private MinioStorageService minioStorageService;
    /** 外部模型依赖：DeepSeek API 调用 Mock 为固定标签响应（真实调用留到启动实测）。 */
    @MockitoBean
    private ChatProvider chatProvider;

    @Test
    @SneakyThrows
    void testFileParsingTool() {
        // 准备：真实上传样本到 MinIO，元数据 storagePath 指向真实对象名，解析走真实存储链路
        String sampleText = "Coffer 是一个基于大模型 Agent 的智能文件存储管理系统，支持自动分类、摘要生成和自然语言搜索。";
        byte[] contentBytes = sampleText.getBytes(StandardCharsets.UTF_8);
        ObjectWriteResponse uploadResp = minioStorageService.uploadFile(
                null,
                new ByteArrayInputStream(contentBytes),
                "text/plain", contentBytes.length);
        String storagePath = uploadResp.object();
        FileMetadata metadata = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("sample.txt")
                .fileSize((long) contentBytes.length)
                .fileType("txt")
                .storagePath(storagePath)
                .status(FileStatus.COMPLETED)
                .build());

        String fileId = String.valueOf(metadata.getId());
        String result = fileParsingTool.parseFile(fileId);

        printResult("FileParsingTool", "fileId=" + fileId + ", storagePath=" + storagePath, result);
        assertNotNull(result, "解析结果不能为 null");
        assertTrue(result.contains("Coffer"), "应解析出真实文件内容，实际: " + result);
        System.out.println("测试通过");
    }

    @Test
    @SneakyThrows
    void testTagGenerationTool() {
        String testContent = "Coffer 是一个基于大模型 Agent 的智能文件存储管理系统，使用 Spring Boot 和 LangChain4j 开发";
        when(chatProvider.chat(any(ChatMessage[].class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("智能存储,Agent,大模型,Spring Boot"))
                        .build());

        String result = tagGenerationTool.generateTags(testContent);

        printResult("TagGenerationTool", "textContent 长度=" + testContent.length(), result);
        assertNotNull(result, "标签结果不能为 null");
        assertTrue(result.contains(",") || result.contains("，"), "标签应以逗号分隔，实际: " + result);
        System.out.println("测试通过");
    }

    @Test
    @SneakyThrows
    void testFileSearchTool() {
        // 准备：H2 中建一条文件 + 已确认标签关联
        FileMetadata file = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("Coffer需求报告.docx")
                .fileSize(2048L)
                .fileType("docx")
                .summary("关于 Coffer 智能存储系统的需求说明文档")
                .status(FileStatus.COMPLETED)
                .build());
        Tag tag = tagRepository.save(Tag.builder().tagName("Coffer").category("项目").build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(file.getId())
                .tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.CONFIRMED)
                .build());

        String keyword = "Coffer";
        String result = fileSearchTool.searchFiles(keyword);

        printResult("FileSearchTool", "keyword=" + keyword, result);
        assertNotNull(result, "搜索结果不能为 null");
        System.out.println("测试通过");
    }

    @Test
    @Transactional
    @SneakyThrows
    void testFileSearchToolOnlyConfirmedTagMatches() {
        // 准备：同一唯一标签分别映射到两个文件——一个 PENDING、一个 CONFIRMED
        // 标签名唯一，Tag 表对 tag_name 有唯一约束，故只建一个 Tag，靠 (file_id, tag_id) 关联区分文件
        String tagName = "独家搜索标签X";
        Tag tag = tagRepository.save(Tag.builder().tagName(tagName).build());

        FileMetadata pendingFile = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("未确认标签文件.txt")
                .fileSize(1L).fileType("txt").status(FileStatus.COMPLETED).build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(pendingFile.getId()).tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build());

        FileMetadata confirmedFile = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("已确认标签文件.txt")
                .fileSize(1L).fileType("txt").status(FileStatus.COMPLETED).build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(confirmedFile.getId()).tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.CONFIRMED).build());

        String result = fileSearchTool.searchFiles(tagName);

        printResult("FileSearchTool(仅已确认标签命中)", "keyword=" + tagName, result);
        assertNotNull(result, "搜索结果不能为 null");
        assertTrue(result.contains("已确认标签文件"), "已确认标签文件应命中，实际: " + result);
        assertTrue(!result.contains("未确认标签文件"), "未确认标签的文件不应命中，实际: " + result);
        System.out.println("测试通过");
    }

    @Test
    @Transactional
    @SneakyThrows
    void testGetRecentUploadsKeepsNewestEightInFifoOrder() {
        // 准备：按“录入顺序”连续保存 9 个文件。uploadTime 由 @CreationTimestamp 自动生成，
        // 极短时间内的多次保存可能同刻，故队列排序以「uploadTime DESC, id DESC」为准——id 随录入顺序
        // 单调递增，等价于“按时间顺序录入、先进先出”。队列保留最近 8 个，最早的第 1 个应被挤出。
        for (int i = 1; i <= 9; i++) {
            fileMetadataRepository.save(FileMetadata.builder()
                    .fileName(String.format("最近上传队列样本%02d.txt", i))
                    .fileSize((long) i)
                    .fileType("txt")
                    .status(i % 2 == 0 ? FileStatus.COMPLETED : FileStatus.PENDING)
                    .build());
        }

        String result = fileSearchTool.getRecentUploads();

        printResult("FileSearchTool.getRecentUploads", "limit=8, FIFO（时间倒序）", result);
        assertNotNull(result, "最近上传结果不能为 null");
        // 最新上传（样本09）应在列，最早上传（样本01）应已被挤出 8 个窗口
        assertTrue(result.contains("最近上传队列样本09"), "最近上传应保留，实际: " + result);
        assertTrue(!result.contains("最近上传队列样本01"), "最早上传应被挤出（仅保留 8 个），实际: " + result);
        // 每条输出应含文件ID，便于模型接 parse_file 读取内容
        assertTrue(result.contains("文件ID："), "返回应包含文件ID 供 parse_file 使用，实际: " + result);
        System.out.println("测试通过");
    }

    /**
     * 格式化打印工具调用结果（工具名 / 参数 / 返回）。
     */
    private void printResult(String toolName, String params, String result) throws JsonProcessingException {
        System.out.println("========================================");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("params", params);
        payload.put("result", result);
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload));
    }
}
