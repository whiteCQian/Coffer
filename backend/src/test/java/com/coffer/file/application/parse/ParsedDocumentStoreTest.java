package com.coffer.file.application.parse;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.file.domain.parse.ParsedDocument;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:parsed_document_store_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2",
        "minio.access-key=test-access-key", "minio.secret-key=test-secret-key"})
class ParsedDocumentStoreTest extends com.coffer.auth.OwnerTestSupport {
    @Autowired FileMetadataRepository files;
    @Autowired ParsedDocumentStore store;
    @Autowired DocumentParseService parser;
    @org.springframework.test.context.bean.override.mockito.MockitoBean FileStoragePort storage;
    private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test void positionsSurvivePersistenceAndDeletionRemovesBody() {
        FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("notes.txt")
                .fileSize(12L).fileType("txt").storagePath(ownerPath("files/notes.txt"))
                .contentSha256(SHA).revision(2L).build());
        ParsedDocument parsed = parser.parseStructured(file,
                new ByteArrayInputStream("first\nsecond".getBytes(StandardCharsets.UTF_8)));
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 12L, SHA, "etag"));
        store.save(file, parsed);
        ParsedDocument loaded = store.load(file);
        assertThat(loaded.status()).isEqualTo(ParseStatus.SUCCESS);
        assertThat(loaded.chunks()).extracting(ParsedDocument.Chunk::start).containsExactly(1, 2);
        assertThat(loaded.content()).isEqualTo("first\nsecond");
        file.setContentSha256("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(store.load(file)).isNull();
        file.setContentSha256(SHA);
        store.deleteFile(file.getId());
        assertThat(store.load(file)).isNull();
    }

    @Test void changedSourceCannotBeStoredUnderAnOlderDigest() {
        FileMetadata file = files.saveAndFlush(FileMetadata.builder().fileName("changed.txt")
                .fileSize(12L).fileType("txt").storagePath(ownerPath("files/changed.txt"))
                .contentSha256(SHA).revision(1L).build());
        ParsedDocument parsed = parser.parseStructured(file,
                new ByteArrayInputStream("first\nsecond".getBytes(StandardCharsets.UTF_8)));
        when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject(
                file.getStoragePath(), 12L, "b".repeat(64), "etag"));

        assertThatThrownBy(() -> store.save(file, parsed)).isInstanceOf(
                com.coffer.file.storage.StorageConflictException.class);
        assertThat(store.load(file)).isNull();
    }
}
