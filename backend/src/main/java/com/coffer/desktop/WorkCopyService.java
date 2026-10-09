package com.coffer.desktop;

import com.coffer.auth.service.*;
import com.coffer.file.application.*;
import com.coffer.file.domain.*;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.storage.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Disk operations sit between committed rows, so every interruption retains an owner-visible intent. */
@Service @Profile("desktop") @RequiredArgsConstructor @OwnerOnly
public class WorkCopyService {
    private static final Set<String> EDITABLE = Set.of("txt", "md", "csv", "docx", "xlsx", "pptx", "odt", "ods", "odp", "pdf", "png", "jpg", "jpeg");
    private static final Set<String> TERMINAL = Set.of("CLOSED", "DISCARDED");
    private final WorkCopyRepository copies;
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final DesktopLibraryLayout layout;
    private final DesktopDataDirectory directory;
    private final WorkCopyOpener opener;
    private final WorkSaveApplicationService saves;
    private final WorkSaveIntentService intents;

    @io.swagger.v3.oas.annotations.media.Schema(name = "WorkCopyView")
    public record View(String id, Long fileId, String fileName, String workPath, String status,
                       String errorCode, boolean busy, boolean modified, String sha256, Long size,
                       String modifiedTime, Long recoveredFileId) { }

    public synchronized View create(Long fileId) {
        var formal = files.findById(fileId).orElseThrow(ResourceNotFoundException::new);
        String type = formal.getFileType() == null ? "" : formal.getFileType().toLowerCase(Locale.ROOT);
        if (!EDITABLE.contains(type)) throw new IllegalArgumentException("此格式不支持外部编辑；请使用不含宏的 Office 文件或普通文档");
        if (formal.getStatus() == FileStatus.PENDING || formal.getStatus() == FileStatus.PROCESSING)
            throw new StorageConflictException("请等待当前文件处理完成");
        layout.ensureOwnerWorkspace();
        var identity = storage.localIdentity(formal.getStoragePath());
        var object = storage.stat(formal.getStoragePath());
        if (identity == null || identity.size() != object.size() || !identity.equals(storage.localIdentity(formal.getStoragePath()))
                || !Objects.equals(formal.getFileSize(), object.size()) || !Objects.equals(formal.getContentSha256(), object.sha256()))
            throw new StorageConflictException("正式文件身份已变化，请先核对原件");
        var copy = new WorkCopy(); copy.setId(UUID.randomUUID().toString()); copy.setFileId(fileId);
        copy.setWorkKey("users/" + TenantContext.requireOwnerId() + "/work/" + copy.getId() + "/edit." + type);
        copy.setBeforeKey(formal.getStoragePath()); copy.setFileName(formal.getFileName());
        copy.setExpectedRevision(formal.getRevision()); copy.setBeforeSha256(object.sha256());
        copy.setBeforeSize(identity.size()); copy.setBeforeModifiedTime(identity.modifiedTime()); copy.setBeforeFileKey(identity.fileKey());
        copy.setStatus("PREPARING"); copy = copies.saveAndFlush(copy);
        try {
            storage.copy(copy.getBeforeKey(), copy.getWorkKey(), copy.getBeforeSha256());
            if (!identity.equals(storage.localIdentity(formal.getStoragePath()))) throw new StorageConflictException("复制期间正式文件身份已变化");
            copy.setStatus("READY"); copies.saveAndFlush(copy);
        } catch (RuntimeException failure) { mark(copy, "INTERRUPTED", "COPY_FAILED"); }
        return view(copy);
    }

    public synchronized View open(String id) {
        var copy = require(id); requireActive(copy);
        if (publishedBaseline(copy) != null) throw new StorageConflictException("此副本已提交；继续编辑请从新版本创建工作副本");
        var observed = view(copy);
        if (observed.busy() || observed.sha256() == null) return observed;
        // Persist before handing a path to the OS; only this derived work path reaches external applications.
        mark(copy, "OPENED", null);
        try { opener.open(path(copy)); }
        catch (IOException | RuntimeException failure) { mark(copy, "INTERRUPTED", "APP_OPEN_FAILED"); }
        return view(copy);
    }

    public synchronized View save(String id) {
        var copy = require(id); requireActive(copy);
        if (copy.getOperationId() != null) {
            var prior = intents.existing(copy.getOperationId());
            if (prior.isPresent() && prior.get().getStatus() == WorkSaveStatus.COMMITTED) {
                copy.setConfirmedSha256(prior.get().getRequestSha256()); mark(copy, "SAVED", null);
                var observed = view(copy);
                if (observed.modified()) mark(copy, "NEEDS_DECISION", "COPY_CHANGED_AFTER_COMMIT");
                return view(copy);
            }
        }
        try (var source = source(copy)) {
            var attributes = SafeLocalPaths.file(path(copy));
            byte[] bytes = source.stream().readAllBytes(); source.verify();
            String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            copy.setOperationId(UUID.nameUUIDFromBytes((TenantContext.requireOwnerId() + ":" + copy.getFileId() + ":" + copy.getId())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            copy.setConfirmedSha256(sha); mark(copy, "SAVING", null);
            var result = saves.save(copy.getFileId(), copy.getExpectedRevision(), copy.getBeforeSha256(), copy.getId(),
                    new EditedFile(copy.getFileName(), bytes), new FileStoragePort.LocalIdentity(copy.getBeforeSize(), copy.getBeforeModifiedTime(), copy.getBeforeFileKey()));
            copy.setOperationId(result.operationId()); mark(copy, "SAVED", null);
            if (attributes.size() != bytes.length) throw new StorageConflictException("副本读取长度不一致");
        } catch (StorageConflictException conflict) { mark(copy, "CONFLICTED", "FORMAL_FILE_CHANGED"); }
        catch (Exception failure) { mark(copy, "INTERRUPTED", "COPY_BUSY_OR_CHANGED"); }
        return view(copy);
    }

    /** Uses the existing save intent and restore API, including its durable object/DB recovery. */
    public synchronized View saveAs(String id) {
        var copy = require(id); requireActive(copy);
        try (var source = source(copy)) {
            String sha = source.sha256();
            // A separate immutable intent is used for save-as; a conflicted save must not silently adopt new bytes.
            String operation = UUID.nameUUIDFromBytes((copy.getId() + ":save-as").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            copy.setOperationId(operation); copy.setConfirmedSha256(sha); mark(copy, "SAVING_AS", null);
            var existing = intents.existing(operation);
            if (existing.isEmpty()) {
                String target = "users/" + TenantContext.requireOwnerId() + "/managed/files/" + operation + "." + extension(copy);
                // Save-as does not send content to a model. A synthetic LOCAL marker never schedules analysis.
                intents.preparePreserve(operation, copy.getFileId(), copy.getFileName(), extension(copy), copy.getBeforeKey(),
                        copy.getExpectedRevision(), copy.getBeforeSha256(), copy.getBeforeSize(), target,
                        SafeLocalPaths.file(path(copy)).size(), sha, UUID.randomUUID().toString());
            }
            var intent = intents.require(operation);
            if (!Objects.equals(intent.getRequestSha256(), sha)) throw new StorageConflictException("另存意图中的副本已变化，请保留并核对台账");
            if (intent.getStatus() == WorkSaveStatus.PREPARED) {
                var object = storage.exists(intent.getTargetKey()) ? storage.stat(intent.getTargetKey())
                        : storage.writeVerified(intent.getTargetKey(), source.stream(), null, intent.getTargetSize(), sha);
                source.verify(); intents.objectWritten(operation, object);
            }
            if (intent.getStatus() != WorkSaveStatus.RECOVERED) intents.manual(operation, "SAVE_AS_REQUESTED");
            copy.setRecoveredFileId(intents.restoreAsNewFile(operation)); mark(copy, "SAVED_AS", null);
        } catch (Exception failure) { mark(copy, "INTERRUPTED", "SAVE_AS_FAILED"); }
        return view(copy);
    }

    public synchronized View close(String id, String action, String sha256) {
        var copy = require(id);
        if (TERMINAL.contains(copy.getStatus())) return view(copy);
        if ("KEEP".equals(action)) {
            mark(copy, publishedBaseline(copy) == null ? "KEPT" : copy.getRecoveredFileId() == null ? "SAVED" : "SAVED_AS", "UNSAVED_COPY_RETAINED");
            return view(copy);
        }
        if ("REPORT_EXIT".equals(action)) { mark(copy, "INTERRUPTED", "APP_EXIT_UNCONFIRMED"); return view(copy); }
        if (!Set.of("CLOSE", "DISCARD").contains(action)) throw new IllegalArgumentException("请选择关闭、保留或放弃副本");
        try (var source = source(copy)) {
            String current = source.sha256();
            if (!Objects.equals(current, sha256)) { mark(copy, "INTERRUPTED", "COPY_CONFIRMATION_CHANGED"); return view(copy); }
            String published = publishedBaseline(copy);
            String baseline = published == null ? copy.getBeforeSha256() : published;
            if ("CLOSE".equals(action) && !Objects.equals(current, baseline)) {
                mark(copy, "NEEDS_DECISION", "UNSAVED_CHANGES");
            } else {
                copy.setConfirmedSha256(current); mark(copy, "DISCARD".equals(action) ? "DISCARDING" : "CLOSING", null);
            }
        } catch (Exception busy) { mark(copy, "INTERRUPTED", "COPY_BUSY_OR_CHANGED"); return view(copy); }
        if (Set.of("DISCARDING", "CLOSING").contains(copy.getStatus())) finishCleanup(copy);
        return view(copy);
    }

    public synchronized View get(String id) { return view(require(id)); }
    public synchronized List<View> recent(int page) {
        if (page < 0 || page > 10000) throw new IllegalArgumentException("副本台账页码无效");
        return copies.findByOrderByCreatedAtDescIdDesc(PageRequest.of(page, 100)).stream().map(this::view).toList();
    }
    public synchronized void recover() {
        // Paginate all unfinished sessions; terminal rows never cause retention loss or starvation.
        for (int page = 0; ; page++) {
            var rows = copies.findByOrderByCreatedAtDescIdDesc(PageRequest.of(page, 100));
            for (var copy : rows) {
                if (Set.of("DISCARDING", "CLOSING").contains(copy.getStatus())) { finishCleanup(copy); continue; }
                if (!TERMINAL.contains(copy.getStatus()) && copy.getOperationId() != null) {
                    var intent = copy.getOperationId() == null ? Optional.<WorkSaveIntent>empty() : intents.existing(copy.getOperationId());
                    if (intent.isPresent() && intent.get().getStatus() == WorkSaveStatus.RECOVERED) {
                        copy.setRecoveredFileId(intent.get().getRecoveredFileId()); mark(copy, "SAVED_AS", null); continue;
                    }
                    if (intent.isPresent() && intent.get().getStatus() == WorkSaveStatus.COMMITTED) {
                        copy.setConfirmedSha256(intent.get().getRequestSha256()); mark(copy, "SAVED", null); continue;
                    }
                    if (intent.isPresent() && Set.of(WorkSaveStatus.CONFLICTED, WorkSaveStatus.MANUAL_REVIEW).contains(intent.get().getStatus())) {
                        mark(copy, "CONFLICTED", "FORMAL_FILE_CHANGED"); continue;
                    }
                }
                if (Set.of("PREPARING", "OPENED", "SAVING", "SAVING_AS").contains(copy.getStatus()))
                    mark(copy, "INTERRUPTED", "BACKEND_EXIT_UNCONFIRMED");
            }
            if (rows.size() < 100) break;
        }
    }
    private void finishCleanup(WorkCopy copy) {
        try {
            if (Files.exists(path(copy), LinkOption.NOFOLLOW_LINKS)) {
                try (var source = source(copy)) {
                    if (!Objects.equals(copy.getConfirmedSha256(), source.sha256())) throw new StorageConflictException("副本内容已变化");
                    source.verify();
                    storage.delete(copy.getWorkKey(), copy.getConfirmedSha256());
                }
            }
            mark(copy, copy.getStatus().equals("DISCARDING") ? "DISCARDED" : "CLOSED", null);
        } catch (Exception failure) { mark(copy, "INTERRUPTED", "COPY_CLEANUP_FAILED"); }
    }
    private WorkCopy require(String id) { return copies.findById(id).orElseThrow(ResourceNotFoundException::new); }
    private void requireActive(WorkCopy copy) {
        if (TERMINAL.contains(copy.getStatus()) || copy.getStatus().equals("SAVED_AS")) throw new StorageConflictException("此工作副本已关闭或另存，请创建新副本");
    }
    private void mark(WorkCopy copy, String status, String error) { copy.setStatus(status); copy.setErrorCode(error); copies.saveAndFlush(copy); }
    private String extension(WorkCopy copy) { return copy.getWorkKey().substring(copy.getWorkKey().lastIndexOf('.') + 1); }
    private Path path(WorkCopy copy) {
        String prefix = "users/" + TenantContext.requireOwnerId() + "/work/" + UUID.fromString(copy.getId()) + "/edit.";
        StorageKey.requireOwned(copy.getWorkKey());
        if (!copy.getWorkKey().equals(prefix + extension(copy)) || !EDITABLE.contains(extension(copy))) throw new StorageConflictException("工作副本路径绑定无效");
        return directory.libraryRoot().resolve(copy.getWorkKey());
    }
    private LocalImportSource source(WorkCopy copy) throws IOException {
        Path path = path(copy); var attrs = SafeLocalPaths.file(path);
        // Office/LibreOffice owner files survive while associated applications hold the document.
        // Word may truncate the first characters of a filename; this UUID directory holds only this session.
        try (var entries = Files.list(path.getParent())) {
            if (entries.anyMatch(entry -> entry.getFileName().toString().startsWith("~$")
                    || entry.getFileName().toString().startsWith(".~lock.") && entry.getFileName().toString().endsWith("#")))
                throw new IOException("Office 文档仍被占用，请关闭后重试");
        }
        return LocalImportSource.open(path, attrs.size(), attrs.lastModifiedTime().toMillis(), LocalImportSource.key(attrs));
    }
    private View view(WorkCopy copy) {
        String sha = null, modifiedTime = null; Long size = null; boolean busy = false;
        if (!Set.of("CLOSED", "DISCARDED").contains(copy.getStatus())) {
            try (var source = source(copy)) {
                sha = source.sha256(); var attrs = SafeLocalPaths.file(path(copy)); size = attrs.size(); modifiedTime = attrs.lastModifiedTime().toString();
            } catch (Exception unavailable) { busy = true; }
        }
        String published = publishedBaseline(copy);
        String baseline = published == null ? copy.getBeforeSha256() : published;
        return new View(copy.getId(), copy.getFileId(), copy.getFileName(), path(copy).toString(), copy.getStatus(),
                copy.getErrorCode(), busy, sha != null && !sha.equals(baseline), sha, size, modifiedTime, copy.getRecoveredFileId());
    }
    private String publishedBaseline(WorkCopy copy) {
        if (copy.getOperationId() == null) return null;
        return intents.existing(copy.getOperationId())
                .filter(intent -> intent.getStatus() == WorkSaveStatus.COMMITTED || intent.getStatus() == WorkSaveStatus.RECOVERED)
                .map(WorkSaveIntent::getRequestSha256).orElse(null);
    }
    private record EditedFile(String name, byte[] bytes) implements MultipartFile {
        public String getName() { return "file"; } public String getOriginalFilename() { return name; }
        public String getContentType() { return "application/octet-stream"; } public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; } public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes, StandardOpenOption.CREATE_NEW); }
    }
}
