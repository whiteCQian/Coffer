package com.coffer.file.application.assembler;

import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileListResponse;
import com.coffer.file.domain.CategoryType;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.tag.api.dto.FileTagInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileResponseAssemblerTest {

    private final FileResponseAssembler assembler = new FileResponseAssembler();

    @Test
    void mapsListResponseWithoutChangingDisplayValues() {
        FileMetadata metadata = FileMetadata.builder()
                .id(7L)
                .fileName("contract.pdf")
                .fileType("pdf")
                .fileSize(12L)
                .summary("summary")
                .category(CategoryType.CONTRACT)
                .status(FileStatus.COMPLETED)
                .archived(true)
                .build();
        FileTagInfo tag = FileTagInfo.builder().tagId(3L).tagName("合同").build();

        FileListResponse response = assembler.toListResponse(metadata, "ALL_CONFIRMED", List.of(tag));

        assertThat(response.getId()).isEqualTo(7L);
        assertThat(response.getCategory()).isEqualTo("CONTRACT");
        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(response.isArchived()).isTrue();
        assertThat(response.getTags()).containsExactly(tag);
    }

    @Test
    void mapsNullEnumsAndDetailFields() {
        FileMetadata metadata = FileMetadata.builder()
                .id(8L)
                .fileName("note.txt")
                .storagePath("files/note.txt")
                .build();

        FileDetailResponse response = assembler.toDetailResponse(metadata, "NO_TAG",
                List.of(), List.of(), "https://preview");

        assertThat(response.getCategory()).isEqualTo("OTHER");
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getStoragePath()).isEqualTo("files/note.txt");
        assertThat(response.getPreviewUrl()).isEqualTo("https://preview");
    }
}
