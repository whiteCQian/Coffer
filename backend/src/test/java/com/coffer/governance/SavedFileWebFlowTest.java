package com.coffer.governance;

import com.coffer.auth.OwnerTestSupport;
import com.coffer.auth.service.TenantContext;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.*;
import com.coffer.governance.application.*;
import com.coffer.governance.api.dto.*;
import com.coffer.governance.infrastructure.persistence.*;
import com.coffer.service.MinioStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Independent Web profile HTTP flow; S3 is a deterministic in-memory fixture, not a deployed MinIO test. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ux_web;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.locations=classpath:db/migration/h2", "coffer.embedding.enabled=false",
        "coffer.import.inbox.enabled=false", "spring.data.redis.port=1", "springdoc.api-docs.enabled=true"})
class SavedFileWebFlowTest extends OwnerTestSupport {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired FileMetadataRepository files;
    @Autowired ArchiveSavedFileService confirmation;
    @Autowired ArchiveOperationService archives;
    @Autowired ArchiveOperationItemRepository items;
    @MockitoBean MinioStorageService storage;
    @MockitoBean com.coffer.tool.TagGenerationTool model;
    private final Map<String,byte[]> objects=new ConcurrentHashMap<>();
    private FileStoragePort.StoredObject statObject(String key) throws Exception {
        byte[] body=objects.get(key);if(body==null)throw new StorageObjectNotFoundException();
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        return new FileStoragePort.StoredObject(key,body.length,sha,sha);
    }
    @Test void saveWithoutAiThenSingleConfirmationDownloadRetryAndOwnerBoundary() throws Exception {
        when(storage.exists(anyString())).thenAnswer(i->objects.containsKey(i.getArgument(0)));
        when(storage.stat(anyString())).thenAnswer(i->statObject(i.getArgument(0)));
        when(storage.write(anyString(),any(InputStream.class),nullable(String.class),anyLong())).thenAnswer(i->{
            String key=i.getArgument(0);objects.put(key,((InputStream)i.getArgument(1)).readAllBytes());return statObject(key);
        });
        when(storage.read(anyString())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        when(storage.readIfUnchanged(anyString(),any())).thenAnswer(i->new ByteArrayInputStream(objects.get(i.getArgument(0))));
        when(storage.copy(anyString(),anyString(),anyString())).thenAnswer(i->{
            String source=i.getArgument(0),target=i.getArgument(1);
            if(!statObject(source).sha256().equals(i.getArgument(2)))throw new StorageConflictException("fixture identity");
            objects.put(target,objects.get(source).clone());return statObject(target);
        });
        doAnswer(i->{objects.remove(i.getArgument(0));return null;}).when(storage).delete(anyString(),anyString());
        var mvc=MockMvcBuilders.webAppContextSetup(context).build();
        var apiDocument=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(json.readTree(apiDocument).path("paths").has("/api/files/{fileId}/confirm-archive")).isTrue();
        java.nio.file.Files.write(java.nio.file.Path.of("../.test-tmp/ux-openapi.json"),apiDocument);
        byte[] body="Fictional park meeting: bring water and a magnifier.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String uploaded=mvc.perform(multipart("/api/files/save").file(new MockMultipartFile("file","虚构公园.txt","text/plain",body))
                .header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var file=files.findByTaskId(json.readTree(uploaded).path("data").path("taskId").asText()).orElseThrow();
        assertThat(file.getStatus()).isEqualTo(FileStatus.FAILED);
        mvc.perform(get("/api/files").param("keyword","虚构公园")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        var request=new ArchiveSavedFileRequest(file.getRevision(),null,null,null,List.of("活动","虚构"));
        String accepted=mvc.perform(post("/api/files/{id}/confirm-archive",file.getId()).contentType("application/json").content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String batch=json.readTree(accepted).path("data").path("batchId").asText();
        for(int n=0;n<200 && !files.findById(file.getId()).orElseThrow().isArchived();n++)Thread.sleep(25);
        assertThat(files.findById(file.getId()).orElseThrow().isArchived()).isTrue();
        assertThat(confirmation.confirm(file.getId(),request).batchId()).isEqualTo(batch);
        assertThat(items.findByFileIdOrderByCreatedAtDesc(file.getId())).hasSize(1);
        mvc.perform(get("/api/files/{id}/content",file.getId()).param("download","true")).andExpect(status().isOk()).andExpect(content().bytes(body));
        mvc.perform(get("/api/governance/operations/recent").param("name","虚构公园")).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        verify(model,never()).generateTagAndCategory(anyString());
        long owner=TenantContext.requireOwnerId();
        long other=context.getBean(com.coffer.auth.infrastructure.AppUserRepository.class).saveAndFlush(new com.coffer.auth.domain.AppUser("other-ux-owner","unused",com.coffer.auth.domain.AuthRole.USER)).getId();
        TenantContext.set(other);
        assertThatThrownBy(()->confirmation.confirm(file.getId(),request)).isInstanceOf(com.coffer.auth.service.ResourceNotFoundException.class);
        TenantContext.set(owner);
    }
}
