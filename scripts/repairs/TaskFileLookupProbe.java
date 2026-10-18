import com.coffer.auth.service.*;
import com.coffer.file.application.FileService;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.task.application.TaskFileLookupService;
import java.util.Optional;
import static org.mockito.Mockito.*;

/** Uses the exact deployed dependencies; every record is synthetic. */
public class TaskFileLookupProbe {
    public static void main(String[] args) {
        var files = mock(FileMetadataRepository.class);
        var service = mock(FileService.class);
        var lookup = new TaskFileLookupService(files, service);
        var first = mock(FileMetadata.class);
        when(first.getId()).thenReturn(41L); when(first.getOwnerId()).thenReturn(7L);
        var second = mock(FileMetadata.class);
        when(second.getId()).thenReturn(42L); when(second.getOwnerId()).thenReturn(7L);
        var dto = new FileDetailResponse(); dto.setId(41L); dto.setFileName("same-name.txt");
        var dto2 = new FileDetailResponse(); dto2.setId(42L); dto2.setFileName("same-name.txt");
        when(files.findByTaskId("task-first")).thenReturn(Optional.of(first));
        when(files.findByTaskId("task-second")).thenReturn(Optional.of(second));
        when(files.findByTaskId("missing")).thenReturn(Optional.empty());
        when(service.getFileDetail(41L)).thenReturn(dto); when(service.getFileDetail(42L)).thenReturn(dto2);
        TenantContext.set(7L);
        if (lookup.find("task-first").getId() != 41L || lookup.find("task-second").getId() != 42L) throw new AssertionError("Wrong duplicate-name file");
        try { lookup.find("missing"); throw new AssertionError("Missing task accepted"); } catch (ResourceNotFoundException expected) { }
        TenantContext.set(8L);
        try { lookup.find("task-first"); throw new AssertionError("Other owner's file exposed"); } catch (ResourceNotFoundException expected) { }
        TenantContext.clear();
        try { lookup.find("task-first"); throw new AssertionError("Anonymous access accepted"); } catch (org.springframework.security.access.AccessDeniedException expected) { }
        if (!TaskFileLookupService.class.isAnnotationPresent(OwnerOnly.class)) throw new AssertionError("Owner authorization missing");
        var authorization = mock(OwnerAuthorization.class);
        when(authorization.requireOwner()).thenThrow(new org.springframework.security.access.AccessDeniedException("synthetic admin"));
        var proxy = new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(lookup);
        proxy.setProxyTargetClass(true); proxy.addAspect(new OwnerAuthorizationAspect(authorization));
        clearInvocations(files, service);
        try { ((TaskFileLookupService) proxy.getProxy()).find("task-first"); throw new AssertionError("Owner gate bypassed"); } catch (org.springframework.security.access.AccessDeniedException expected) { }
        verifyNoInteractions(files, service);
        System.out.println("PASS exact deployed dependencies: duplicate-name task identity, absent task, cross-owner denial, anonymous denial, owner authorization aspect before repository access");
    }
}
