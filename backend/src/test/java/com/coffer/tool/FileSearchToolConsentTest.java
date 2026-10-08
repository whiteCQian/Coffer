package com.coffer.tool;

import com.coffer.auth.service.OwnerAuthorization;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.model.runtime.ModelContentGate;
import com.coffer.model.runtime.ModelConsentRequiredException;
import com.coffer.model.runtime.ModelRuntimeCapability;
import com.coffer.service.ChatCitationCollector;
import com.coffer.service.HybridSearchService;
import com.coffer.service.PrivateFileAccess;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FileSearchToolConsentTest {
    @Test void unapprovedHitIsOmittedAndTheUserGetsAnAuthorizationHint() {
        var files = mock(FileMetadataRepository.class);
        var search = mock(HybridSearchService.class);
        var access = mock(PrivateFileAccess.class);
        var auth = mock(OwnerAuthorization.class);
        var citations = mock(ChatCitationCollector.class);
        var gate = mock(ModelContentGate.class);
        var tool = new FileSearchTool(files, mock(FileTagMappingRepository.class),
                mock(TagRepository.class), search, access, auth, citations);
        ReflectionTestUtils.setField(tool, "contentGate", gate);
        FileMetadata blocked = FileMetadata.builder().id(1L).revision(2L).fileName("private.txt").build();
        FileMetadata allowed = FileMetadata.builder().id(2L).revision(3L).fileName("public.txt").build();
        when(auth.requireOwner()).thenReturn(7L);
        when(search.searchWithEvidence(7L, "query")).thenReturn(List.of(
                new HybridSearchService.SearchEvidence(blocked, 0.8, "LEXICAL"),
                new HybridSearchService.SearchEvidence(allowed, 0.7, "LEXICAL")));
        when(access.requireVersion(1L, 2L)).thenReturn(blocked);
        when(access.requireVersion(2L, 3L)).thenReturn(allowed);
        doThrow(new ModelConsentRequiredException()).when(gate)
                .requireFileAllowed(blocked, ModelRuntimeCapability.CHAT);

        String result = tool.searchFiles(7L, "query");

        assertThat(result).contains("public.txt", "尚未授权").doesNotContain("private.txt");
        verify(citations, never()).capture(eq(blocked), anyDouble(), anyString(), any());
        verify(citations).capture(eq(allowed), eq(0.7), eq("LEXICAL"), isNull());
    }
}
