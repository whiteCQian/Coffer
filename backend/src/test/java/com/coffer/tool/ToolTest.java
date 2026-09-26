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
    DocumentParseService parser = mock(DocumentParseService.class);
    MinioStorageService storage = mock(MinioStorageService.class);
    PrivateFileAccess files = mock(PrivateFileAccess.class);
    OwnerAuthorization auth = mock(OwnerAuthorization.class);
    ChatCitationCollector citations = mock(ChatCitationCollector.class);
    FileParsingTool tool = new FileParsingTool(parser, storage, files, auth, citations);
    FileMetadata file = FileMetadata.builder().id(1L).fileName("a.txt").storagePath("users/1/files/a.txt").build();
    @BeforeEach void setup() {
        when(auth.requireOwner()).thenReturn(1L);
        when(files.require(1L)).thenReturn(file);
        when(storage.getFileStream(isNull(), anyString())).thenReturn(new ByteArrayInputStream(new byte[0]));
    }
    @Test void versionChangedDuringParsingDoesNotReleaseContentOrCitation() {
        when(parser.extractTextFromFile(anyString(), any())).thenReturn(ParseResult.success("STALE_SECRET"));
        when(files.requireVersion(1L, 0L)).thenThrow(new FileVersionConflictException());
        assertThat(tool.parseFile("1")).doesNotContain("STALE_SECRET");
        verifyNoInteractions(citations);
    }
    @Test void parserDiagnosticsDoNotBecomeModelContext() {
        when(parser.extractTextFromFile(anyString(), any())).thenReturn(ParseResult.error(ParseStatus.FAILED, "internal-path-secret"));
        assertThat(tool.parseFile("1")).doesNotContain("internal-path-secret");
        verifyNoInteractions(citations);
    }
    @Test void successfulParsingCapturesSourceAndClosesInput() throws Exception {
        var stream = spy(new ByteArrayInputStream(new byte[0]));
        when(storage.getFileStream(isNull(), anyString())).thenReturn(stream);
        when(parser.extractTextFromFile(anyString(), any())).thenReturn(ParseResult.success("text"));
        assertThat(tool.parseFile("1")).isEqualTo("text");
        verify(citations).capture(file, 1d, "PARSE", "text");
        verify(stream).close();
    }
    @Test void foreignOwnerIsRejectedBeforeLookup() {
        assertThatThrownBy(() -> tool.parseFile(2L, "1")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(files, storage, parser, citations);
    }
}
