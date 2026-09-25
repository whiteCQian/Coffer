package com.coffer.tag.api;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.tag.application.TagConfirmationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件标签确认接口测试：验证成功响应、参数校验（@Valid → 全局异常处理器）、
 * 业务异常（IllegalArgumentException → code 400）与其他异常（code 500），
 * 以及标签候选池接口（仅 CONFIRMED、按文件数去重倒序）。
 *
 * <p>TagConfirmationService 以 {@link MockitoBean} 隔离，仅测控制器层；
 * 标签候选池直接走真实 Repository（纯读投影），种子数据以 {@link Transactional} 回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FileTagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TagConfirmationService tagConfirmationService;

    /** 候选池真实查询依赖，用于播种关联数据并校验结果。 */
    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    @Autowired
    private TagRepository tagRepository;

    @BeforeEach
    void isolateCommittedDataFromNonTransactionalIntegrationTests() {
        fileTagMappingRepository.deleteAll();
        tagRepository.deleteAll();
        fileMetadataRepository.deleteAll();
    }

    @Test
    void confirmTagSuccess() throws Exception {
        doNothing().when(tagConfirmationService).confirmTag(1L, 2L);

        mockMvc.perform(post("/api/files/tags/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"tagId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));
    }

    @Test
    void confirmTagMissingFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/files/tags/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("tagId: 标签 ID 不能为空"));
    }

    @Test
    void confirmTagBusinessErrorReturns400() throws Exception {
        doThrow(new IllegalArgumentException("文件与标签的关联不存在"))
                .when(tagConfirmationService).confirmTag(anyLong(), anyLong());

        mockMvc.perform(post("/api/files/tags/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":999,\"tagId\":888}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文件与标签的关联不存在"));
    }

    @Test
    void confirmTagUnexpectedErrorReturns500() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(tagConfirmationService).confirmTag(anyLong(), anyLong());

        mockMvc.perform(post("/api/files/tags/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"tagId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("确认标签失败"));
    }

    @Test
    void rejectTagSuccess() throws Exception {
        doNothing().when(tagConfirmationService).rejectTag(1L, 2L, "修正标签");

        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"tagId\":2,\"newTagName\":\"修正标签\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("success"));
    }

    @Test
    void rejectTagWithoutCorrection() throws Exception {
        doNothing().when(tagConfirmationService).rejectTag(1L, 2L, null);

        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"tagId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void rejectTagMissingFieldReturns400() throws Exception {
        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("tagId: 标签 ID 不能为空"));
    }

    @Test
    void rejectTagBusinessErrorReturns400() throws Exception {
        doThrow(new IllegalArgumentException("文件与标签的关联不存在"))
                .when(tagConfirmationService).rejectTag(anyLong(), anyLong(), any());

        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":999,\"tagId\":888}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文件与标签的关联不存在"));
    }

    @Test
    void rejectTagUnexpectedErrorReturns500() throws Exception {
        doThrow(new RuntimeException("db down"))
                .when(tagConfirmationService).rejectTag(anyLong(), anyLong(), any());

        mockMvc.perform(post("/api/files/tags/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileId\":1,\"tagId\":2,\"newTagName\":\"修正标签\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("拒绝标签失败"));
    }

    @Test
    void getTagCandidatesCountsConfirmedMappingsDistinctPerFileDesc() throws Exception {
        // f1/f2 确认采用「合同」，f3 确认采用「发票」、f2 的「发票」仍待确认，f1 的「人员」已拒绝
        FileMetadata f1 = saveFile("甲合同.pdf");
        FileMetadata f2 = saveFile("乙合同.pdf");
        FileMetadata f3 = saveFile("丙发票.pdf");
        Tag heTong = tagRepository.save(Tag.builder().tagName("合同").build());
        Tag faPiao = tagRepository.save(Tag.builder().tagName("发票").build());
        Tag renYuan = tagRepository.save(Tag.builder().tagName("人员").build());
        saveMapping(f1.getId(), heTong.getId(), ConfirmationStatus.CONFIRMED);
        saveMapping(f2.getId(), heTong.getId(), ConfirmationStatus.CONFIRMED);
        saveMapping(f2.getId(), faPiao.getId(), ConfirmationStatus.PENDING_CONFIRMATION);
        saveMapping(f3.getId(), faPiao.getId(), ConfirmationStatus.CONFIRMED);
        saveMapping(f1.getId(), renYuan.getId(), ConfirmationStatus.REJECTED);

        mockMvc.perform(get("/api/files/tags/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("合同"))
                .andExpect(jsonPath("$.data[0].fileCount").value(2))
                .andExpect(jsonPath("$.data[1].name").value("发票"))
                .andExpect(jsonPath("$.data[1].fileCount").value(1));
    }

    @Test
    void getTagCandidatesEmptyWhenNothingConfirmed() throws Exception {
        FileMetadata fm = saveFile("待确认.pdf");
        Tag heTong = tagRepository.save(Tag.builder().tagName("合同").build());
        saveMapping(fm.getId(), heTong.getId(), ConfirmationStatus.PENDING_CONFIRMATION);

        mockMvc.perform(get("/api/files/tags/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private FileMetadata saveFile(String name) {
        return fileMetadataRepository.save(FileMetadata.builder()
                .fileName(name).fileSize(1L).fileType("pdf").status(FileStatus.COMPLETED).build());
    }

    private void saveMapping(Long fileId, Long tagId, ConfirmationStatus status) {
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fileId).tagId(tagId).confirmationStatus(status)
                .confirmedAt(status == ConfirmationStatus.PENDING_CONFIRMATION ? null : LocalDateTime.now())
                .build());
    }
}
