package com.coffer.service;

import com.coffer.file.domain.FileMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCitationCollectorTest {

    private final PrivateFileAccess access = org.mockito.Mockito.mock(PrivateFileAccess.class);
    private final com.coffer.auth.service.OwnerAuthorization auth = org.mockito.Mockito.mock(com.coffer.auth.service.OwnerAuthorization.class);
    private final ChatCitationCollector collector = new ChatCitationCollector(access, auth);

    @org.junit.jupiter.api.BeforeEach void setup() {
        org.mockito.Mockito.when(auth.requireOwner()).thenReturn(1L);
        org.mockito.Mockito.when(access.current(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
    }

    @Test
    void capturesRealFilesDeduplicatesAndKeepsBestScore() {
        FileMetadata file = FileMetadata.builder()
                .id(7L)
                .fileName("合同.pdf")
                .fileType("pdf")
                .build();

        org.mockito.Mockito.when(access.requireVersion(7L, 0L)).thenReturn(file);
        collector.begin();
        collector.capture(file, 0.10d, "KEYWORD", "合同摘要");
        collector.capture(file, 0.80d, "VECTOR", "合同摘要");

        var citations = collector.finish();

        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).getFileId()).isEqualTo(7L);
        assertThat(citations.get(0).getScore()).isEqualTo(0.80d);
        assertThat(citations.get(0).getRetrievalType()).isEqualTo("KEYWORD,VECTOR");
    }

    @Test
    void ignoresToolCallsOutsideAnActiveConversationCapture() {
        FileMetadata file = FileMetadata.builder()
                .id(8L)
                .fileName("说明.txt")
                .build();

        collector.capture(file, 1.0d, "KEYWORD", "说明");

        assertThat(collector.finish()).isEmpty();
    }

    @Test
    void separateParsedPagesKeepSeparateSourceLocations() {
        FileMetadata file = FileMetadata.builder().id(9L).fileName("pages.pdf").fileType("pdf").build();
        org.mockito.Mockito.when(access.requireVersion(9L, 0L)).thenReturn(file);
        var first = new com.coffer.file.domain.parse.ParsedDocument.Chunk("first", "PAGE", 1, 1, 1, 5);
        var second = new com.coffer.file.domain.parse.ParsedDocument.Chunk("second", "PAGE", 2, 2, 1, 6);
        var parsed = new com.coffer.file.domain.parse.ParsedDocument(9L, 0, "pdf", "bounded-1",
                com.coffer.file.domain.parse.ParseStatus.SUCCESS, null, java.util.List.of(first, second));
        collector.begin();
        collector.captureParsed(file, 1d, parsed, first);
        collector.captureParsed(file, 1d, parsed, second);
        var citations = collector.finish();
        assertThat(citations).extracting(c -> c.getSourceStart()).containsExactly(1, 2);
        assertThat(citations).extracting(c -> c.getSnippet()).containsExactly("first", "second");
    }
}
