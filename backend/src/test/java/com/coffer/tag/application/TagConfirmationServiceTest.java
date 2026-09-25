package com.coffer.tag.application;

import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 标签确认/拒绝服务集成测试：模拟人工「确认 → 拒绝 → 拒绝并修正」的完整闭环，
 * 并覆盖幂等、关联不存在与参数校验等边界场景。
 *
 * <p>{@link Transactional} 保证每个用例的种子数据在事务内自动回滚，互不污染。
 * 种子数据按真实链路创建：{@link FileMetadata} + {@link Tag} + {@code PENDING_CONFIRMATION} 关联记录。
 */
@SpringBootTest
@Transactional
class TagConfirmationServiceTest {

    @Autowired
    private TagConfirmationService tagConfirmationService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private FileTagMappingRepository fileTagMappingRepository;

    /** 一次性种子数据：文件 ID + 标签 ID。 */
    private record Seeded(Long fileId, Long tagId) {
    }

    /**
     * 创建真实文件、标签与待确认关联记录。
     */
    private Seeded seedPendingMapping(String tagName) {
        FileMetadata fm = fileMetadataRepository.save(FileMetadata.builder()
                .fileName("闭环测试-" + tagName + ".txt")
                .fileSize(1L)
                .fileType("txt")
                .status(FileStatus.COMPLETED)
                .build());
        Tag tag = tagRepository.save(Tag.builder().tagName(tagName).build());
        fileTagMappingRepository.save(FileTagMapping.builder()
                .fileId(fm.getId())
                .tagId(tag.getId())
                .confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION)
                .build());
        return new Seeded(fm.getId(), tag.getId());
    }

    private FileTagMapping mapping(Long fileId, Long tagId) {
        return fileTagMappingRepository.findByFileIdAndTagId(fileId, tagId).orElseThrow();
    }

    @Test
    void confirmTagMarksConfirmedWithTimestamp() {
        Seeded seed = seedPendingMapping("确认闭环标签");

        tagConfirmationService.confirmTag(seed.fileId(), seed.tagId());

        FileTagMapping saved = mapping(seed.fileId(), seed.tagId());
        assertThat(saved.getConfirmationStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
        assertThat(saved.getConfirmedAt()).isNotNull()
                .isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void rejectTagWithoutCorrectionMarksRejected() {
        Seeded seed = seedPendingMapping("拒绝闭环标签");

        tagConfirmationService.rejectTag(seed.fileId(), seed.tagId(), null);

        FileTagMapping saved = mapping(seed.fileId(), seed.tagId());
        assertThat(saved.getConfirmationStatus()).isEqualTo(ConfirmationStatus.REJECTED);
        assertThat(saved.getConfirmedAt()).isNotNull();
        // 未提供修正标签，不创建新标签、备注为空
        assertThat(saved.getConfirmationNote()).isNull();
        assertThat(tagRepository.findByTagName("拒绝闭环标签")).isPresent();
    }

    @Test
    void rejectTagWithCorrectionCreatesPendingMapping() {
        Seeded seed = seedPendingMapping("原标签");

        tagConfirmationService.rejectTag(seed.fileId(), seed.tagId(), "修正标签");

        // 原关联被拒绝，修正意图写入备注
        FileTagMapping rejected = mapping(seed.fileId(), seed.tagId());
        assertThat(rejected.getConfirmationStatus()).isEqualTo(ConfirmationStatus.REJECTED);
        assertThat(rejected.getConfirmedAt()).isNotNull();
        assertThat(rejected.getConfirmationNote()).isEqualTo("修正标签");
        // 修正标签已创建，并以待确认状态挂回同一文件（需再次人工确认）
        Tag newTag = tagRepository.findByTagName("修正标签").orElse(null);
        assertThat(newTag).isNotNull();
        FileTagMapping correction = mapping(seed.fileId(), newTag.getId());
        assertThat(correction.getConfirmationStatus()).isEqualTo(ConfirmationStatus.PENDING_CONFIRMATION);
    }

    @Test
    void confirmTagIsIdempotentWhenAlreadyConfirmed() {
        Seeded seed = seedPendingMapping("幂等确认标签");
        tagConfirmationService.confirmTag(seed.fileId(), seed.tagId());
        LocalDateTime firstConfirmedAt = mapping(seed.fileId(), seed.tagId()).getConfirmedAt();

        tagConfirmationService.confirmTag(seed.fileId(), seed.tagId());

        FileTagMapping saved = mapping(seed.fileId(), seed.tagId());
        assertThat(saved.getConfirmationStatus()).isEqualTo(ConfirmationStatus.CONFIRMED);
        // 幂等：确认时间不被重置
        assertThat(saved.getConfirmedAt()).isEqualTo(firstConfirmedAt);
    }

    @Test
    void rejectTagIsIdempotentWhenAlreadyRejected() {
        Seeded seed = seedPendingMapping("幂等拒绝标签");
        tagConfirmationService.rejectTag(seed.fileId(), seed.tagId(), "旧修正");
        LocalDateTime firstConfirmedAt = mapping(seed.fileId(), seed.tagId()).getConfirmedAt();

        tagConfirmationService.rejectTag(seed.fileId(), seed.tagId(), "新修正");

        FileTagMapping saved = mapping(seed.fileId(), seed.tagId());
        assertThat(saved.getConfirmationStatus()).isEqualTo(ConfirmationStatus.REJECTED);
        assertThat(saved.getConfirmedAt()).isEqualTo(firstConfirmedAt);
        assertThat(saved.getConfirmationNote()).isEqualTo("旧修正");
    }

    @Test
    void rejectTagReusesExistingTagInsteadOfDuplicate() {
        Seeded seed = seedPendingMapping("待拒标签");
        Tag existing = tagRepository.save(Tag.builder().tagName("复用标签").build());

        tagConfirmationService.rejectTag(seed.fileId(), seed.tagId(), "  复用标签  ");

        // 复用已有标签，不重复创建；新关联为待确认状态
        assertThat(tagRepository.findByTagName("复用标签")).isPresent();
        FileTagMapping correction = mapping(seed.fileId(), existing.getId());
        assertThat(correction.getConfirmationStatus()).isEqualTo(ConfirmationStatus.PENDING_CONFIRMATION);
    }

    @Test
    void confirmTagNotFoundThrows() {
        assertThatThrownBy(() -> tagConfirmationService.confirmTag(999L, 888L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关联不存在");
    }

    @Test
    void rejectTagNotFoundThrows() {
        assertThatThrownBy(() -> tagConfirmationService.rejectTag(999L, 888L, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关联不存在");
    }

    @Test
    void confirmTagNullParamsThrows() {
        assertThatThrownBy(() -> tagConfirmationService.confirmTag(null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tagConfirmationService.confirmTag(1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectTagNullParamsThrows() {
        assertThatThrownBy(() -> tagConfirmationService.rejectTag(null, 1L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tagConfirmationService.rejectTag(1L, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
