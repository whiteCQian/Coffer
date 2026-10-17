package com.coffer.desktop;

import com.coffer.auth.service.*;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.file.infrastructure.storage.SafeLocalPaths;
import com.coffer.file.storage.*;
import com.coffer.service.FileVersionConflictException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Viewing has no work-copy row, save intent, model call or requirement to confirm an editor's exit. */
@Service @Profile("desktop") @OwnerOnly
public class FileViewService {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("txt", "md", "csv", "docx", "xlsx", "pptx", "odt", "ods", "odp", "pdf", "png", "jpg", "jpeg", "gif", "webp", "bmp");
    private static final Pattern CACHE = Pattern.compile("f[0-9]+-r[0-9]+-([a-f0-9]{64})-[a-f0-9-]{36}");
    private final FileMetadataRepository files;
    private final FileStoragePort storage;
    private final DesktopDataDirectory directory;
    private final WorkCopyOpener opener;
    private final Map<String, Path> cached = new HashMap<>();
    private final Set<Path> handedOff = new HashSet<>();
    public FileViewService(FileMetadataRepository files, FileStoragePort storage, DesktopDataDirectory directory, WorkCopyOpener opener) {
        this.files=files; this.storage=storage; this.directory=directory; this.opener=opener;
    }
    public record View(Long fileId, Long revision, String status, String errorCode, String message) { }

    public synchronized View open(Long id, Long revision) {
        if (id == null || id < 1) throw new IllegalArgumentException("文件 ID 无效");
        var file = files.findById(id).orElseThrow(ResourceNotFoundException::new);
        if (revision != null && !Objects.equals(revision, file.getRevision())) throw new FileVersionConflictException();
        String type = Objects.toString(file.getFileType(), "").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) return failed(file, "UNSUPPORTED_TYPE", "此格式不支持直接打开，请下载后查看");
        var object = storage.stat(file.getStoragePath());
        if (object.size() < 0 || object.size() > MAX_BYTES || !Objects.equals(file.getFileSize(), object.size())
                || file.getContentSha256() == null || !file.getContentSha256().matches("[a-f0-9]{64}")
                || !file.getContentSha256().equals(object.sha256())) throw new StorageConflictException("文件正文与记录不一致");
        try {
            Path root = child(child(child(directory.root(), ".coffer-runtime"), "view-cache"), Long.toString(TenantContext.requireOwnerId()));
            cleanup(root);
            String key = root + ":" + id + ":" + file.getRevision() + ":" + object.sha256() + ":" + file.getFileName();
            Path view = cached.get(key);
            if (view == null || !matches(view, object.sha256(), object.size())) {
                Path folder = child(root, "f"+id+"-r"+file.getRevision()+"-"+object.sha256()+"-"+UUID.randomUUID());
                view = folder.resolve(viewName(file.getFileName(), type));
                try {
                 try (var input = storage.readIfUnchanged(file.getStoragePath(), object);
                     var output = Files.newOutputStream(view, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[65536]; long size=0; int read;
                    while ((read=input.read(buffer))!=-1) { size+=read; if(size>object.size()) throw new IOException("正文长度变化"); output.write(buffer,0,read); }
                    if (size != object.size()) throw new IOException("正文长度变化");
                }
                if (!matches(view, object.sha256(), object.size())) throw new IOException("查看副本校验失败");
                } catch (IOException | RuntimeException failed) {
                    // This fresh directory has never been handed to an application.
                    try { if (Files.exists(view,LinkOption.NOFOLLOW_LINKS)) { SafeLocalPaths.file(view); Files.delete(view); } Files.delete(folder); }
                    catch (IOException | SecurityException retained) { /* Retry cleanup later if a filesystem error prevents it. */ }
                    throw failed;
                }
                readOnly(view, true);
                if (cached.size() >= 256) cached.clear();
                cached.put(key, view);
            }
            // Recheck cached handoffs too: loading metadata and preparing bytes can race a new revision.
            var latest = files.findById(id).orElseThrow(ResourceNotFoundException::new);
            if (!Objects.equals(latest.getRevision(), file.getRevision()) || !Objects.equals(latest.getContentSha256(), object.sha256())) throw new FileVersionConflictException();
            Files.setLastModifiedTime(view.getParent(), FileTime.fromMillis(System.currentTimeMillis()));
            handedOff.add(view.getParent());
            try { opener.open(view); }
            catch (IOException | RuntimeException failure) { return failed(file,"APP_OPEN_FAILED","无法用默认应用打开，请检查该格式的默认应用设置后重试"); }
            return new View(id,file.getRevision(),"OPENED",null,"已用默认应用打开查看副本；编辑文件请使用“编辑文件”");
        } catch (IOException failure) { return failed(file,"VIEW_PREPARE_FAILED","无法准备查看副本，请检查文件是否可用及磁盘空间后重试"); }
    }
    private View failed(FileMetadata file,String code,String message) { return new View(file.getId(),file.getRevision(),"FAILED",code,message); }
    private Path child(Path root,String name) throws IOException {
        SafeLocalPaths.directory(root); Path child=root.resolve(name);
        if(!Files.exists(child,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(child);
        return SafeLocalPaths.directory(child);
    }
    static String viewName(String original,String type) {
        String name=Objects.toString(original,"文件").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("[ .]+$","");
        int dot=name.lastIndexOf('.'); if(dot>0) name=name.substring(0,dot);
        if(name.isBlank())name="文件";
        if(name.length()>80)name=name.substring(0,80);
        if(name.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))name="_"+name;
        return name+"."+type;
    }
    private static boolean matches(Path file,String sha,long size) {
        try {
            if(SafeLocalPaths.file(file).size()!=size||size>MAX_BYTES)return false;
            var digest=MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int read;long count=0;while((read=input.read(buffer))!=-1){count+=read;if(count>MAX_BYTES)return false;digest.update(buffer,0,read);}}
            return HexFormat.of().formatHex(digest.digest()).equals(sha);
        }catch(Exception unavailable){return false;}
    }
    private static void readOnly(Path file,boolean value) {
        try { Files.setAttribute(file,"dos:readonly",value,LinkOption.NOFOLLOW_LINKS); }
        catch(IOException|UnsupportedOperationException ignored) { /* The separate copy always protects the formal object. */ }
    }
    /** Reclaim only old, unchanged, single-file caches; current-session handoffs and edited/locked copies stay. */
    void cleanup(Path root) throws IOException {
        SafeLocalPaths.directory(root);
        long cutoff=System.currentTimeMillis()-Duration.ofDays(7).toMillis();
        try(var folders=Files.list(root)) {
            for(Path folder:folders.limit(512).toList()) {
                var name=CACHE.matcher(folder.getFileName().toString());
                if(!name.matches()||handedOff.contains(folder))continue;
                try {
                    SafeLocalPaths.directory(folder);
                    if(Files.getLastModifiedTime(folder,LinkOption.NOFOLLOW_LINKS).toMillis()>cutoff)continue;
                    List<Path> entries;try(var stream=Files.list(folder)){entries=stream.limit(2).toList();}
                    if(entries.size()!=1)continue;Path file=entries.get(0);
                    var attrs=SafeLocalPaths.file(file);
                    if(!matches(file,name.group(1),attrs.size()))continue;
                    readOnly(file,false);
                    try {
                    try(var channel=FileChannel.open(file,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                        var lock=channel.tryLock()) { if(lock==null)continue; }
                    // Windows rejects unlinking an application-held document; keep it for a later attempt.
                    Files.delete(file);Files.delete(folder);
                    } finally { if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))readOnly(file,true); }
                }catch(Exception retained){ /* No cache problem should prevent viewing another file. */ }
            }
        }
    }
}
