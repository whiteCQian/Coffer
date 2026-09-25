package com.coffer.governance;

import com.coffer.config.GovernancePreviewProperties;
import com.coffer.dto.VisionResult;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.governance.api.dto.CreateGovernancePreviewRequest;
import com.coffer.governance.api.dto.ConfirmGovernancePreviewRequest;
import com.coffer.governance.api.dto.GovernancePreviewResponse;
import com.coffer.governance.application.GovernancePreviewService;
import com.coffer.governance.application.ArchiveObjectNameService;
import com.coffer.governance.api.dto.SkipGovernancePreviewItemRequest;
import com.coffer.governance.api.dto.UpdateGovernancePreviewItemRequest;
import com.coffer.governance.api.dto.ReanalyzeGovernanceFilesRequest;
import com.coffer.governance.domain.GovernancePreviewBatch;
import com.coffer.governance.domain.GovernancePreviewBatchStatus;
import com.coffer.governance.domain.GovernancePreviewItem;
import com.coffer.governance.domain.GovernancePreviewItemStatus;
import com.coffer.governance.domain.GovernancePreviewSource;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewBatchRepository;
import com.coffer.governance.infrastructure.persistence.GovernancePreviewItemRepository;
import com.coffer.service.MinioStorageService;
import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.service.VisionModelService;
import com.coffer.tool.TagGenerationTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovernancePreviewServiceTest {

    @Mock
    private GovernancePreviewBatchRepository batchRepository;
    @Mock
    private GovernancePreviewItemRepository itemRepository;
    @Mock
    private FileMetadataRepository fileMetadataRepository;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private DocumentParseService documentParseService;
    @Mock
    private TagGenerationTool tagGenerationTool;
    @Mock
    private VisionModelService visionModelService;
    @Mock
    private ArchiveObjectNameService archiveObjectNameService;

    private GovernancePreviewProperties properties;
    private GovernancePreviewService service;
    private final List<GovernancePreviewItem> savedItems = new ArrayList<>();
    private GovernancePreviewBatch savedBatch;

    @BeforeEach
    void setUp() {
        properties = new GovernancePreviewProperties();
        properties.setExpirationMinutes(60);
        properties.setAnalysisModel("test-model");
        service = new GovernancePreviewService(batchRepository, itemRepository, fileMetadataRepository,
                minioStorageService, documentParseService, tagGenerationTool, visionModelService,
                archiveObjectNameService, properties, new ObjectMapper());
        lenient().when(batchRepository.saveAndFlush(any(GovernancePreviewBatch.class))).thenAnswer(invocation -> {
            savedBatch = invocation.getArgument(0);
            return savedBatch;
        });
        lenient().when(batchRepository.save(any(GovernancePreviewBatch.class))).thenAnswer(invocation -> {
            savedBatch = invocation.getArgument(0);
            return savedBatch;
        });
        lenient().when(itemRepository.save(any(GovernancePreviewItem.class))).thenAnswer(invocation -> {
            GovernancePreviewItem item = invocation.getArgument(0);
            if (savedItems.stream().noneMatch(existing -> existing == item)) {
                savedItems.add(item);
            }
            return item;
        });
        lenient().when(itemRepository.findByPreviewIdOrderByIdAsc(anyString())).thenAnswer(invocation -> savedItems.stream()
                .filter(item -> invocation.getArgument(0, String.class).equals(item.getPreviewId()))
                .toList());
        lenient().when(batchRepository.findByRequestId(anyString())).thenReturn(Optional.empty());
        lenient().when(minioStorageService.statFile(any(), anyString()))
                .thenReturn(new MinioStorageService.ObjectSnapshot("etag-1", 12));
        lenient().when(minioStorageService.objectExists(any(), anyString())).thenReturn(false);
        lenient().when(archiveObjectNameService.generateArchivePath(any(), anyString(), any()))
                .thenReturn("contracts/2026/09/20/preview.txt");
    }

    @Test
    void createsPreviewWithoutMutatingFormalFileMetadataOrTags() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同", "采购")));

        GovernancePreviewResponse response = service.create(request(List.of(1L), "preview-request-1"));

        assertThat(response.status()).isEqualTo(GovernancePreviewBatchStatus.READY);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.READY);
        assertThat(response.items().get(0).suggestedCategory()).isEqualTo("CONTRACT");
        assertThat(response.items().get(0).suggestedTags()).containsExactly("合同", "采购");
        assertThat(response.items().get(0).suggestedPath()).isEqualTo("contracts/2026/09/20/preview.txt");
        assertThat(metadata.getCategory()).isEqualTo(CategoryType.REPORT);
        assertThat(metadata.getSummary()).isEqualTo("旧摘要");
        assertThat(metadata.getStoragePath()).isEqualTo("files/note.txt");
        verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
    }

    @Test
    void reanalyzeCreatesNewPreviewWithReanalyzeSourceWithoutFormalWrite() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        ReanalyzeGovernanceFilesRequest request = new ReanalyzeGovernanceFilesRequest();
        request.setFileIds(List.of(1L));
        request.setRequestId("reanalyze-request-1");
        GovernancePreviewResponse response = service.reanalyze(request);

        assertThat(response.status()).isEqualTo(GovernancePreviewBatchStatus.READY);
        assertThat(savedBatch.getSource()).isEqualTo(GovernancePreviewSource.REANALYZE);
        assertThat(savedBatch.getRequestId()).isEqualTo("reanalyze-request-1");
        verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
    }

    @Test
    void recordsPartialFailureAndKeepsSuccessfulItemPreviewable() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata), Optional.empty());
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse response = service.create(request(List.of(1L, 2L), "preview-request-2"));

        assertThat(response.status()).isEqualTo(GovernancePreviewBatchStatus.PARTIAL_READY);
        assertThat(response.readyCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.items()).extracting(item -> item.status())
                .containsExactly(GovernancePreviewItemStatus.READY, GovernancePreviewItemStatus.FAILED);
    }

    @Test
    void expiresPreviewAndItemsWithoutTouchingFileMetadata() {
        GovernancePreviewBatch batch = GovernancePreviewBatch.builder()
                .previewId("preview-expired")
                .source(GovernancePreviewSource.UPLOAD)
                .runMode(GovernanceRunMode.API)
                .requestId("request-expired")
                .createdBy("LOCAL_USER")
                .status(GovernancePreviewBatchStatus.READY)
                .totalCount(1)
                .readyCount(1)
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .build();
        GovernancePreviewItem item = GovernancePreviewItem.builder()
                .id(1L).previewId("preview-expired").fileId(1L)
                .status(GovernancePreviewItemStatus.READY).build();
        when(batchRepository.findByPreviewId("preview-expired")).thenReturn(Optional.of(batch));
        savedItems.add(item);

        GovernancePreviewResponse response = service.get("preview-expired");

        assertThat(response.status()).isEqualTo(GovernancePreviewBatchStatus.EXPIRED);
        assertThat(response.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.EXPIRED);
        verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
    }

    @Test
    void returnsExistingPreviewForAnIdempotentRetry() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse first = service.create(request(List.of(1L), "preview-request-idempotent"));
        when(batchRepository.findByRequestId("preview-request-idempotent")).thenReturn(Optional.of(savedBatch));
        GovernancePreviewResponse second = service.create(request(List.of(1L), "preview-request-idempotent"));

        assertThat(second.previewId()).isEqualTo(first.previewId());
        assertThat(savedItems).hasSize(1);
    }

    @Test
    void regeneratesAFreshPreviewFromThePreviousFileSet() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse first = service.create(request(List.of(1L), "preview-request-first"));
        GovernancePreviewBatch previous = savedBatch;
        when(batchRepository.findByPreviewId(first.previewId())).thenReturn(Optional.of(previous));

        GovernancePreviewResponse regenerated = service.regenerate(first.previewId(),
                new com.coffer.governance.api.dto.RegenerateGovernancePreviewRequest("preview-request-retry"));

        assertThat(regenerated.previewId()).isNotEqualTo(first.previewId());
        assertThat(regenerated.source()).isEqualTo(GovernancePreviewSource.RETRY);
        assertThat(regenerated.items()).extracting(item -> item.fileId()).containsExactly(1L);
    }

    @Test
    void marksTargetPathConflictAsPartialReadyInsteadOfReady() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));
        when(minioStorageService.objectExists(any(), anyString())).thenReturn(true);

        GovernancePreviewResponse response = service.create(request(List.of(1L), "preview-request-conflict"));

        assertThat(response.status()).isEqualTo(GovernancePreviewBatchStatus.PARTIAL_READY);
        assertThat(response.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.CONFLICTED);
    }

    @Test
    void editsSuggestionAndKeepsFormalMetadataUntouched() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse created = service.create(request(List.of(1L), "preview-request-edit"));
        GovernancePreviewItem item = savedItems.get(0);
        item.setId(101L);
        when(batchRepository.findByPreviewId(created.previewId())).thenReturn(Optional.of(savedBatch));
        when(itemRepository.findById(101L)).thenReturn(Optional.of(item));

        GovernancePreviewResponse updated = service.updateItem(created.previewId(), 101L,
                new UpdateGovernancePreviewItemRequest(
                        "采购合同-已修订.txt", CategoryType.CONTRACT, "修订后的合同摘要", List.of("采购", "合同")));

        assertThat(updated.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.EDITED);
        assertThat(updated.items().get(0).suggestedFileName()).isEqualTo("采购合同-已修订.txt");
        assertThat(updated.items().get(0).suggestedTags()).containsExactly("采购", "合同");
        assertThat(metadata.getCategory()).isEqualTo(CategoryType.REPORT);
        verify(fileMetadataRepository, never()).save(any(FileMetadata.class));
    }

    @Test
    void skipsOnePreviewItemWithoutChangingTheOtherItem() {
        FileMetadata first = metadata(1L, "first.txt", "files/first.txt");
        FileMetadata second = metadata(2L, "second.txt", "files/second.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(first));
        when(fileMetadataRepository.findById(2L)).thenReturn(Optional.of(second));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse created = service.create(request(List.of(1L, 2L), "preview-request-skip"));
        savedItems.get(0).setId(201L);
        savedItems.get(1).setId(202L);
        when(batchRepository.findByPreviewId(created.previewId())).thenReturn(Optional.of(savedBatch));
        when(itemRepository.findById(201L)).thenReturn(Optional.of(savedItems.get(0)));

        GovernancePreviewResponse skipped = service.skipItem(created.previewId(), 201L,
                new SkipGovernancePreviewItemRequest("无需归档"));

        assertThat(skipped.items()).extracting(item -> item.status())
                .containsExactly(GovernancePreviewItemStatus.SKIPPED, GovernancePreviewItemStatus.READY);
        assertThat(skipped.items().get(0).skipReason()).isEqualTo("无需归档");
    }

    @Test
    void confirmsOnlySelectedItemsAndLeavesTheRestActionable() {
        FileMetadata first = metadata(1L, "first.txt", "files/first.txt");
        FileMetadata second = metadata(2L, "second.txt", "files/second.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(first));
        when(fileMetadataRepository.findById(2L)).thenReturn(Optional.of(second));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse created = service.create(request(List.of(1L, 2L), "preview-request-confirm"));
        savedItems.get(0).setId(301L);
        savedItems.get(1).setId(302L);
        when(batchRepository.findByPreviewId(created.previewId())).thenReturn(Optional.of(savedBatch));

        GovernancePreviewResponse confirmed = service.confirm(created.previewId(),
                new ConfirmGovernancePreviewRequest(List.of(301L), false));

        assertThat(confirmed.status()).isEqualTo(GovernancePreviewBatchStatus.PARTIALLY_CONFIRMED);
        assertThat(confirmed.items()).extracting(item -> item.status())
                .containsExactly(GovernancePreviewItemStatus.CONFIRMED, GovernancePreviewItemStatus.READY);
    }

    @Test
    void revalidationMarksChangedSourceAsConflictInsteadOfConfirming() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse created = service.create(request(List.of(1L), "preview-request-source-change"));
        GovernancePreviewItem item = savedItems.get(0);
        item.setId(401L);
        when(batchRepository.findByPreviewId(created.previewId())).thenReturn(Optional.of(savedBatch));
        when(minioStorageService.statFile(any(), anyString()))
                .thenReturn(new MinioStorageService.ObjectSnapshot("etag-changed", 12));

        GovernancePreviewResponse result = service.confirm(created.previewId(),
                new ConfirmGovernancePreviewRequest(List.of(401L), false));

        assertThat(result.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.CONFLICTED);
        assertThat(result.items().get(0).errorCode()).isEqualTo("SOURCE_CHANGED");
    }

    @Test
    void cancelsPreviewAndAllUnconfirmedItems() {
        FileMetadata metadata = metadata(1L, "note.txt", "files/note.txt");
        when(fileMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        when(minioStorageService.getFileStream(any(), anyString()))
                .thenReturn(new ByteArrayInputStream("合同正文".getBytes()));
        when(documentParseService.extractTextWithFallback(anyString(), any()))
                .thenReturn(ParseResult.builder().status(ParseStatus.SUCCESS).content("合同正文").build());
        when(tagGenerationTool.generateTagAndCategory("合同正文"))
                .thenReturn(new TagAndCategoryResult(CategoryType.CONTRACT, List.of("合同")));

        GovernancePreviewResponse created = service.create(request(List.of(1L), "preview-request-cancel"));
        when(batchRepository.findByPreviewId(created.previewId())).thenReturn(Optional.of(savedBatch));

        GovernancePreviewResponse cancelled = service.cancel(created.previewId());

        assertThat(cancelled.status()).isEqualTo(GovernancePreviewBatchStatus.CANCELLED);
        assertThat(cancelled.items().get(0).status()).isEqualTo(GovernancePreviewItemStatus.CANCELLED);
    }

    private CreateGovernancePreviewRequest request(List<Long> fileIds, String requestId) {
        return new CreateGovernancePreviewRequest(fileIds, requestId,
                GovernancePreviewSource.UPLOAD, GovernanceRunMode.API);
    }

    private FileMetadata metadata(Long id, String fileName, String storagePath) {
        return FileMetadata.builder()
                .id(id)
                .fileName(fileName)
                .fileSize(12L)
                .fileType("txt")
                .storagePath(storagePath)
                .category(CategoryType.REPORT)
                .summary("旧摘要")
                .revision(0L)
                .archived(false)
                .build();
    }
}
