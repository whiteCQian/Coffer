package com.coffer.governance;

import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.application.ArchiveOperationPersistenceService;
import com.coffer.governance.domain.ArchiveOperationBatch;
import com.coffer.governance.domain.ArchiveOperationSource;
import com.coffer.governance.domain.ArchiveOperationItem;
import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationBatchRepository;
import com.coffer.governance.infrastructure.persistence.ArchiveOperationItemRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewBatchRepository;
import com.coffer.service.MinioStorageService;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:archive_reanalysis_tags;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key",
        "minio.secret-key=test-secret-key"
})
@ActiveProfiles("dev")
class ArchiveReanalysisTagReplacementTest extends com.coffer.auth.OwnerTestSupport {

    @Autowired
    private ArchiveOperationPersistenceService persistenceService;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private FileTagMappingRepository mappingRepository;

    @Autowired
    private GovernancePreviewBatchRepository previewBatchRepository;

    @Autowired
    private ArchiveOperationBatchRepository operationBatchRepository;

    @Autowired
    private ArchiveOperationItemRepository operationItemRepository;

    @MockitoBean
    private MinioStorageService minioStorageService;

    @BeforeEach
    void clean() {
        mappingRepository.deleteAll();
        operationItemRepository.deleteAll();
        operationBatchRepository.deleteAll();
        previewBatchRepository.deleteAll();
        fileMetadataRepository.deleteAll();
        tagRepository.deleteAll();
    }

    @Test
    void reanalysisConfirmationReplacesExistingFileTags() {
        Scenario scenario = seed(GovernancePreviewSource.REANALYZE);

        persistenceService.applyFormalState(scenario.item().getId(), "new-etag", 12,
                "new summary", List.of("new-tag"));

        List<String> tagNames = mappingRepository.findByFileId(scenario.file().getId()).stream()
                .map(mapping -> tagRepository.findById(mapping.getTagId()).orElseThrow().getTagName())
                .toList();
        assertThat(tagNames).containsExactly("new-tag");
        assertThat(mappingRepository.findByFileIdAndConfirmationStatus(
                scenario.file().getId(), ConfirmationStatus.CONFIRMED)).hasSize(1);
    }

    @Test
    void ordinaryPreviewConfirmationAlsoReplacesExistingFileTags() {
        Scenario scenario = seed(GovernancePreviewSource.UPLOAD);

        persistenceService.applyFormalState(scenario.item().getId(), "new-etag", 12,
                "new summary", List.of("new-tag"));

        List<String> tagNames = mappingRepository.findByFileId(scenario.file().getId()).stream()
                .map(mapping -> tagRepository.findById(mapping.getTagId()).orElseThrow().getTagName())
                .map(String::toString)
                .toList();
        assertThat(tagNames).containsExactly("new-tag");
    }

    @Test
    void retryAfterFormalCommitAlsoReconcilesExistingFileTags() {
        Scenario scenario = seed(GovernancePreviewSource.REANALYZE);

        persistenceService.applyFormalState(scenario.item().getId(), "new-etag", 12,
                "new summary", List.of("new-tag"));

        Tag staleTag = tagRepository.saveAndFlush(Tag.builder().tagName("stale-tag").build());
        mappingRepository.saveAndFlush(FileTagMapping.builder()
                .fileId(scenario.file().getId()).tagId(staleTag.getId())
                .confirmationStatus(ConfirmationStatus.CONFIRMED).build());

        // The second call enters the alreadyCommitted/idempotent branch.
        persistenceService.applyFormalState(scenario.item().getId(), "new-etag", 12,
                "new summary", List.of("new-tag"));

        List<String> tagNames = mappingRepository.findByFileId(scenario.file().getId()).stream()
                .map(mapping -> tagRepository.findById(mapping.getTagId()).orElseThrow().getTagName())
                .toList();
        assertThat(tagNames).containsExactly("new-tag");
    }

    private Scenario seed(GovernancePreviewSource source) {
        FileMetadata file = fileMetadataRepository.saveAndFlush(FileMetadata.builder()
                .fileName("source.txt")
                .fileSize(12L)
                .fileType("txt")
                .storagePath("files/source.txt")
                .status(FileStatus.COMPLETED)
                .category(CategoryType.REPORT)
                .revision(0L)
                .build());
        Tag oldTag = tagRepository.saveAndFlush(Tag.builder().tagName("old-tag").build());
        mappingRepository.saveAndFlush(FileTagMapping.builder()
                .fileId(file.getId()).tagId(oldTag.getId())
                .confirmationStatus(ConfirmationStatus.CONFIRMED).build());

        String suffix = source.name().toLowerCase() + "-" + file.getId();
        GovernancePreviewBatch preview = previewBatchRepository.saveAndFlush(GovernancePreviewBatch.builder()
                .previewId("preview-" + suffix)
                .source(source)
                .runMode(GovernanceRunMode.API)
                .requestId("preview-request-" + suffix)
                .totalCount(1)
                .readyCount(1)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build());
        ArchiveOperationBatch batch = operationBatchRepository.saveAndFlush(ArchiveOperationBatch.builder()
                .batchId("archive-" + suffix)
                .previewId(preview.getPreviewId())
                .source(ArchiveOperationSource.PREVIEW_CONFIRMATION)
                .runMode(GovernanceRunMode.API)
                .requestId("archive-request-" + suffix)
                .totalCount(1)
                .build());
        ArchiveOperationItem item = operationItemRepository.saveAndFlush(ArchiveOperationItem.builder()
                .batchId(batch.getBatchId())
                .fileId(file.getId())
                .itemKey(batch.getBatchId() + ":" + file.getId())
                .expectedRevision(0L)
                .sourceFileName(file.getFileName())
                .targetFileName(file.getFileName())
                .sourceCategory(CategoryType.REPORT.name())
                .targetCategory(CategoryType.REPORT.name())
                .sourcePath(file.getStoragePath())
                .targetPath(file.getStoragePath())
                .sourceEtag("old-etag")
                .sourceSize(12L)
                .build());
        return new Scenario(file, item);
    }

    private record Scenario(FileMetadata file, ArchiveOperationItem item) {
    }
}
