import com.coffer.auth.service.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.storage.FileStoragePort;
import com.coffer.governance.application.*;
import com.coffer.governance.api.dto.*;
import com.coffer.governance.domain.*;
import com.coffer.governance.infrastructure.persistence.*;
import com.coffer.tag.domain.*;
import com.coffer.tag.infrastructure.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;

/** Contract checks against the exact deployed classes; all records are fictional. */
public class ArchiveSavedFileProbe {
  public static void main(String[] args) {
    var files=mock(FileMetadataRepository.class);var mappings=mock(FileTagMappingRepository.class);var tags=mock(TagRepository.class);
    var batches=mock(GovernancePreviewBatchRepository.class);var items=mock(GovernancePreviewItemRepository.class);var operations=mock(ArchiveOperationItemRepository.class);
    var previews=mock(GovernancePreviewService.class);var names=mock(ArchiveObjectNameService.class);var storage=mock(FileStoragePort.class);
    var service=new ArchiveSavedFileService(files,mappings,tags,batches,items,operations,previews,names,storage,new ObjectMapper());
    var file=spy(new FileMetadata());when(file.getOwnerId()).thenReturn(7L);file.setId(41L);file.setUploadTime(java.time.LocalDateTime.of(2026,10,18,12,0));file.setRevision(3L);file.setFileName("fictional.txt");file.setStoragePath("fictional/inbox.txt");file.setStatus(FileStatus.COMPLETED);file.setSummary("fictional summary");file.setCategory(CategoryType.OTHER);
    when(files.findByIdForUpdate(41L)).thenReturn(Optional.of(file));when(batches.findByRequestId(anyString())).thenReturn(Optional.empty());when(operations.findByFileIdOrderByCreatedAtDesc(41L)).thenReturn(List.of());
    when(storage.stat(file.getStoragePath())).thenReturn(new FileStoragePort.StoredObject("fictional/inbox.txt",20,"abc","etag"));
    when(names.generateArchivePath(any(),any(),any())).thenReturn("fictional/archive.txt");
    when(mappings.findByFileId(41L)).thenReturn(List.of(FileTagMapping.builder().tagId(1L).confirmationStatus(ConfirmationStatus.CONFIRMED).build(),FileTagMapping.builder().tagId(2L).confirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION).build(),FileTagMapping.builder().tagId(3L).confirmationStatus(ConfirmationStatus.REJECTED).build()));
    when(tags.findAllById(List.of(1L,2L))).thenReturn(List.of(Tag.builder().tagName("confirmed").build(),Tag.builder().tagName("pending").build()));
    var result=mock(ArchiveOperationBatchResponse.class);var response=mock(GovernancePreviewResponse.class);when(response.latestOperation()).thenReturn(result);when(previews.confirm(anyString(),any())).thenReturn(response);
    TenantContext.set(7L);var request=new ArchiveSavedFileRequest(3L,null,null,null,null);
    if(service.confirm(41L,request)!=result)throw new AssertionError("No durable operation returned");
    var capture=org.mockito.ArgumentCaptor.forClass(GovernancePreviewItem.class);verify(items).saveAndFlush(capture.capture());var saved=capture.getValue();
    if(!saved.getSuggestedFileName().equals("fictional.txt")||!saved.getSuggestedSummary().equals("fictional summary")||!saved.getSuggestedTags().equals("[\"confirmed\",\"pending\"]"))throw new AssertionError("Saved content or tags changed");
    verify(previews,never()).create(any());verify(previews,never()).reanalyze(any());verify(storage,only()).stat(file.getStoragePath());
    var existing=GovernancePreviewBatch.builder().previewId("existing").build();when(batches.findByRequestId("saved-file-41-3")).thenReturn(Optional.of(existing));when(previews.get("existing")).thenReturn(response);clearInvocations(items,previews);
    if(service.confirm(41L,request)!=result)throw new AssertionError("Idempotency failed");verifyNoInteractions(items);verify(previews,never()).confirm(anyString(),any());
    when(batches.findByRequestId(anyString())).thenReturn(Optional.empty());
    try{service.confirm(41L,new ArchiveSavedFileRequest(2L,null,null,null,null));throw new AssertionError("Stale revision accepted");}catch(IllegalArgumentException expected){}
    file.setStatus(FileStatus.PROCESSING);try{service.confirm(41L,request);throw new AssertionError("Parsing file accepted");}catch(IllegalArgumentException expected){}file.setStatus(FileStatus.COMPLETED);
    var active=ArchiveOperationItem.builder().executionStatus(ArchiveOperationItemExecutionStatus.COPYING).rollbackStatus(ArchiveOperationItemRollbackStatus.NOT_REQUESTED).build();when(operations.findByFileIdOrderByCreatedAtDesc(41L)).thenReturn(List.of(active));try{service.confirm(41L,request);throw new AssertionError("Concurrent operation accepted");}catch(IllegalArgumentException expected){}
    TenantContext.set(8L);try{service.confirm(41L,request);throw new AssertionError("Other owner accepted");}catch(ResourceNotFoundException expected){}
    TenantContext.clear();try{service.confirm(41L,request);throw new AssertionError("Anonymous accepted");}catch(org.springframework.security.access.AccessDeniedException expected){}
    var authorization=mock(OwnerAuthorization.class);when(authorization.requireOwner()).thenThrow(new org.springframework.security.access.AccessDeniedException("fictional admin"));var proxy=new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(service);proxy.setProxyTargetClass(true);proxy.addAspect(new OwnerAuthorizationAspect(authorization));clearInvocations(files);
    try{((ArchiveSavedFileService)proxy.getProxy()).confirm(41L,request);throw new AssertionError("Owner gate bypassed");}catch(org.springframework.security.access.AccessDeniedException expected){}verifyNoInteractions(files);
    System.out.println("PASS deployed contracts: saved metadata/tags, no model calls, delegated archive engine, duplicate request, stale revision, parsing/concurrent operation denial, cross-owner/anonymous/admin denial");
  }
}
