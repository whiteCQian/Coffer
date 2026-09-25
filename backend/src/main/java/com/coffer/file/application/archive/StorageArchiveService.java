package com.coffer.file.application.archive;

import com.coffer.file.domain.FileMetadata;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.service.MinioStorageService;
import com.coffer.governance.application.ArchiveObjectNameService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分类目录归档服务：在标签确认（CONFIRMED）后把 MinIO 对象物理移动到受控分类目录。
 *
 * <p>移动三步顺序（见分类目录归档任务清单 T4.2，保证一致性、别先删旧）：
 * <ol>
 *   <li>copyObject（旧 → 新）：失败即返回，文件仍在旧路径，无损失；</li>
 *   <li>{@link #markArchived}：{@code REQUIRES_NEW} 独立事务原子更新 storagePath + archived；</li>
 *   <li>deleteFile（旧路径）：失败仅多一份冗余对象，可后台 GC，不影响正确性。</li>
 * </ol>
 * <b>绝不可先删旧再更新 DB</b>：若 DB 更新失败文件将「丢」；反过来 delete 失败只是多一个孤儿对象。
 *
 * <p>本服务方法<b>不标注 {@code @Transactional}</b>：MinIO 移动是外部 IO，不应放入 DB 事务内
 * （移动后若事务回滚 → DB 未变但 MinIO 已搬，永久不一致）。归档整体 try/catch 吞异常，
 * 保证归档失败不影响标签确认本身（用户已确认成功，失败可下次重试）。
 */
@Slf4j
@Service
public class StorageArchiveService {

    private final FileMetadataRepository fileMetadataRepository;
    private final ArchiveObjectNameService archiveObjectNameService;
    private final MinioStorageService minioStorageService;
    /**
     * 自身代理（{@code @Lazy} 自注入）：跨过 Spring 自调用边界，使本类内部调用
     * {@link #markArchived} 时 {@code @Transactional} 仍能被代理拦截。
     *
     * <p>注意：不能靠 {@code @RequiredArgsConstructor} 自动生成构造器——Lombok 不会把
     * 字段上的 {@code @Lazy} 复制到构造参数，Spring 会当成硬循环依赖抛
     * {@code BeanCurrentlyInCreationException}。故此处手写构造器，把 {@code @Lazy} 标注在参数上。
     */
    private final StorageArchiveService self;

    public StorageArchiveService(FileMetadataRepository fileMetadataRepository,
                                 ArchiveObjectNameService archiveObjectNameService,
                                 MinioStorageService minioStorageService,
                                 @Lazy StorageArchiveService self) {
        this.fileMetadataRepository = fileMetadataRepository;
        this.archiveObjectNameService = archiveObjectNameService;
        this.minioStorageService = minioStorageService;
        this.self = self;
    }

    /**
     * 归档指定文件：计算目标路径 → 复制 → 更新 storagePath + archived → 删除旧对象。
     *
     * <p>归档失败仅记日志不抛出，不阻塞调用方（标签确认 / 事务提交回调）。
     *
     * @param fileId 文件 ID
     */
    public void archive(Long fileId) {
        try {
            doArchive(fileId);
        } catch (Exception e) {
            log.error("文件归档失败 fileId={}: {}", fileId, e.getMessage(), e);
        }
    }

    private void doArchive(Long fileId) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId).orElse(null);
        if (metadata == null) {
            log.warn("归档跳过：文件不存在 fileId={}", fileId);
            return;
        }
        if (metadata.isArchived()) {
            log.info("归档跳过：文件已归档 fileId={}", fileId);
            return;
        }
        String source = metadata.getStoragePath();
        if (source == null || source.isBlank()) {
            log.warn("归档跳过：文件缺少存储路径 fileId={}", fileId);
            return;
        }
        String target = archiveObjectNameService.generateArchivePath(
                metadata.getCategory(), metadata.getFileName(), metadata.getUploadTime());
        if (target.equals(source)) {
            // 兜底幂等：目标与源相同（理论不发生，路径格式不可能相等），标记已归档即可。
            // 仍走 REQUIRES_NEW（markArchived），避免在 AFTER_COMMIT 陈旧持久化上下文里内联 save 静默丢写。
            self.markArchived(fileId, target);
            return;
        }

        // 第一步：复制（服务端拷贝；失败即返回，文件仍在旧路径，无损失）
        minioStorageService.copyObject(source, target);

        // 第二步：REQUIRES_NEW 独立事务原子更新 storagePath + archived（见 markArchived 说明）
        self.markArchived(fileId, target);
        log.info("文件归档完成 fileId={}, {} → {}", fileId, source, target);

        // 第三步：删除旧对象（失败仅多一份冗余对象，不影响正确性）
        try {
            minioStorageService.deleteFile(null, source);
        } catch (Exception e) {
            log.warn("归档后删除旧对象失败（留冗余对象，可后台 GC）fileId={}, source={}: {}",
                    fileId, source, e.getMessage());
        }
    }

    /**
     * 在独立（{@code REQUIRES_NEW}）事务中原子更新 {@code storagePath} 与 {@code archived}。
     *
     * <p>为什么必须 {@code REQUIRES_NEW}（而不是默认 REQUIRED）：归档由
     * {@code @TransactionalEventListener(AFTER_COMMIT)} 在确认事务提交后触发，但 Spring 在
     * {@code triggerAfterCommit} 之后才执行 {@code cleanupAfterCompletion} 解绑线程事务资源，
     * 因此监听器执行时，确认事务的 {@code EntityManager} 仍绑定在当前线程。若用 REQUIRED，
     * 此处仓库调用会 JOIN 这个「已在 DB 层提交」的陈旧事务：{@code findById} 返回陈旧
     * 持久化上下文中的托管实体，{@code save} 的 merge 只写入陈旧上下文、其提交为空操作、
     * Hibernate 不再 flush —— UPDATE 静默丢失，形成「MinIO 已搬走、DB 仍指向旧路径」的
     * 永久不一致。{@code REQUIRES_NEW} 挂起陈旧绑定、开启全新事务与全新 EntityManager，
     * 保证 UPDATE 真正提交落库。
     *
     * @param fileId 文件 ID
     * @param target 归档目标路径
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markArchived(Long fileId, String target) {
        FileMetadata metadata = fileMetadataRepository.findById(fileId).orElse(null);
        if (metadata == null || metadata.isArchived()) {
            return;
        }
        metadata.setStoragePath(target);
        metadata.setArchived(true);
        fileMetadataRepository.save(metadata);
    }
}
