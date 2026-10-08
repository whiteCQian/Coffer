package com.coffer.tool;

import com.coffer.auth.service.*;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.file.domain.*;
import com.coffer.file.domain.parse.*;
import com.coffer.service.*;
import org.junit.jupiter.api.*;
import java.io.ByteArrayInputStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ToolTest {
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    DocumentParseService parser = mock(DocumentParseService.class);
    MinioStorageService storage = mock(MinioStorageService.class);
    PrivateFileAccess files = mock(PrivateFileAccess.class);
    OwnerAuthorization auth = mock(OwnerAuthorization.class);
    ChatCitationCollector citations = mock(ChatCitationCollector.class);
    FileParsingTool tool = new FileParsingTool(parser, storage, files, auth, citations);
    FileMetadata file = FileMetadata.builder().id(1L).fileName("a.txt").fileSize(0L)
            .contentSha256(EMPTY_SHA256).storagePath("users/1/files/a.txt").build();
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "contentGate",
                mock(com.coffer.model.runtime.ModelContentGate.class));
        when(auth.requireOwner()).thenReturn(1L);
        when(files.require(1L)).thenReturn(file);
        var observed = new com.coffer.file.storage.FileStoragePort.StoredObject(
                file.getStoragePath(), 0, EMPTY_SHA256, "etag-1");
        when(storage.stat(file.getStoragePath())).thenReturn(observed);
        when(storage.readIfUnchanged(file.getStoragePath(), observed))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
    }
    @Test void versionChangedDuringParsingDoesNotReleaseContentOrCitation() {
        when(parser.parseStructured(eq(file), any())).thenReturn(parsed("STALE_SECRET"));
        when(files.requireVersion(1L, 0L)).thenThrow(new FileVersionConflictException());
        assertThat(tool.parseFile("1")).doesNotContain("STALE_SECRET");
        verifyNoInteractions(citations);
    }
    @Test void parserDiagnosticsDoNotBecomeModelContext() {
        when(parser.parseStructured(eq(file), any())).thenReturn(new ParsedDocument(1L, 0, "txt", "test",
                ParseStatus.FAILED, "internal-path-secret", java.util.List.of()));
        assertThat(tool.parseFile("1")).doesNotContain("internal-path-secret");
        verifyNoInteractions(citations);
    }
    @Test void successfulParsingCapturesSourceAndClosesInput() throws Exception {
        var stream = spy(new ByteArrayInputStream(new byte[0]));
        when(storage.readIfUnchanged(eq(file.getStoragePath()), any())).thenReturn(stream);
        when(parser.parseStructured(eq(file), any())).thenReturn(parsed("text"));
        assertThat(tool.parseFile("1")).isEqualTo("text");
        verify(citations).captureParsed(eq(file), eq(1d), any(ParsedDocument.class), any(ParsedDocument.Chunk.class));
        verify(stream).close();
    }
    @Test void foreignOwnerIsRejectedBeforeLookup() {
        assertThatThrownBy(() -> tool.parseFile(2L, "1")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(files, storage, parser, citations);
    }
    private ParsedDocument parsed(String text) {
        return new ParsedDocument(1L, 0, "txt", "test", ParseStatus.SUCCESS, null,
                java.util.List.of(new ParsedDocument.Chunk(text, "LINE", 1, 1, 1, text.length())));
    }
}
