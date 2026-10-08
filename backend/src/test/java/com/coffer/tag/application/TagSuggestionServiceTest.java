package com.coffer.tag.application;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.model.provider.ChatProvider;
import com.coffer.model.runtime.ModelConsentRequiredException;
import com.coffer.model.runtime.ModelContentGate;
import com.coffer.model.runtime.ModelRuntimeCapability;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TagSuggestionServiceTest {
    private final ChatProvider chat = mock(ChatProvider.class);
    private final FileTagMappingRepository mappings = mock(FileTagMappingRepository.class);
    private final FileMetadataRepository files = mock(FileMetadataRepository.class);
    private final ModelContentGate gate = mock(ModelContentGate.class);
    private final TagSuggestionService service = new TagSuggestionService(chat, new ObjectMapper(), mappings, files, gate);

    @Test
    void deniedSourceLabelNeverReachesModel() {
        var allowed = candidate("日常", 1);
        var denied = candidate("私人病历", 1);
        when(mappings.findTagCandidates()).thenReturn(List.of(allowed, denied));
        var allowedSource = source("日常", 1);
        var deniedSource = source("私人病历", 2);
        when(mappings.findConfirmedTagSources(Set.of("日常", "私人病历")))
                .thenReturn(List.of(allowedSource, deniedSource));
        var first = FileMetadata.builder().id(1L).build();
        var second = FileMetadata.builder().id(2L).build();
        when(files.findAllById(Set.of(1L, 2L))).thenReturn(List.of(first, second));
        doThrow(new ModelConsentRequiredException()).when(gate)
                .requireFileAllowed(second, ModelRuntimeCapability.CHAT);
        when(chat.chat(any(ChatMessage[].class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("{\"tags\":[\"日常\",\"私人病历\"]}")).build());

        assertThat(service.suggest("找一下生活记录")).extracting("name").containsExactly("日常");
        var messages = org.mockito.ArgumentCaptor.forClass(ChatMessage[].class);
        verify(chat).chat(messages.capture());
        assertThat(((SystemMessage) messages.getValue()[0]).text())
                .contains("日常").doesNotContain("私人病历");
    }

    @Test
    void noEligibleSourceMeansNoRemoteCall() {
        var denied = candidate("私人病历", 1);
        when(mappings.findTagCandidates()).thenReturn(List.of(denied));
        var deniedSource = source("私人病历", 2);
        when(mappings.findConfirmedTagSources(Set.of("私人病历")))
                .thenReturn(List.of(deniedSource));
        var file = FileMetadata.builder().id(2L).build();
        when(files.findAllById(Set.of(2L))).thenReturn(List.of(file));
        doThrow(new ModelConsentRequiredException()).when(gate)
                .requireFileAllowed(file, ModelRuntimeCapability.CHAT);

        assertThat(service.suggest("找一下生活记录")).isEmpty();
        verifyNoInteractions(chat);
    }

    @Test
    void sharedLabelRequiresEveryContributingFile() {
        var candidate = candidate("客户名称", 2);
        var firstSource = source("客户名称", 1);
        var secondSource = source("客户名称", 2);
        when(mappings.findTagCandidates()).thenReturn(List.of(candidate));
        when(mappings.findConfirmedTagSources(Set.of("客户名称")))
                .thenReturn(List.of(firstSource, secondSource));
        var first = FileMetadata.builder().id(1L).build();
        var second = FileMetadata.builder().id(2L).build();
        when(files.findAllById(Set.of(1L, 2L))).thenReturn(List.of(first, second));
        doThrow(new ModelConsentRequiredException()).when(gate)
                .requireFileAllowed(second, ModelRuntimeCapability.CHAT);

        assertThat(service.suggest("查找合作伙伴")).isEmpty();
        verifyNoInteractions(chat);
    }

    private FileTagMappingRepository.TagCandidate candidate(String name, long count) {
        var candidate = mock(FileTagMappingRepository.TagCandidate.class);
        when(candidate.getName()).thenReturn(name);
        when(candidate.getCnt()).thenReturn(count);
        return candidate;
    }

    private FileTagMappingRepository.TagCandidateSource source(String name, long fileId) {
        var source = mock(FileTagMappingRepository.TagCandidateSource.class);
        when(source.getName()).thenReturn(name);
        when(source.getFileId()).thenReturn(fileId);
        return source;
    }
}
