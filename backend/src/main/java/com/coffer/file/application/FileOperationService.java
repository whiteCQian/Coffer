package com.coffer.file.application;

import com.coffer.task.application.TaskRegistrationService;
import com.coffer.file.domain.FileMetadata;
import com.coffer.file.domain.FileStatus;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.service.VectorCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 文件操作服务：重命名、失败文件重试、删除等改动文件元数据/生命周期状态的操作。
 *
 * <p>正式分类和存储位置变更只由治理预览、归档台账及其补偿流程管理；本服务不执行对象移动。
 *
 * <p>重试仅登记状态（事务内），异步重新解析由 Controller 在事务提交后调用
 * {@code AsyncFileProcessor.processFileAsync(newTaskId)} 触发——与上传链路同构，
 * 保证异步线程读到的是已提交的 PENDING 记录。
 *
 * <p>删除只清理 DB（事务内），MinIO 对象删除由 Controller 在事务外执行，沿用「DB 为准、
 * 失败留孤儿可 GC」的归档哲学。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class FileOperationService {

    private final FileMetadataRepository fileMetadataRepository;
    private final TaskRegistrationService taskRegistrationService;
    private final FileTagMappingRepository fileTagMappingRepository;
    private final VectorCleanupService vectorCleanupService;
    private final StorageDeletionTaskService storageDeletionTaskService;

    /**
     * 失败文件重试：仅 {@code FileStatus.FAILED} 可重试，事务内重建 PENDING 任务并回退文件状态。
     *
     * <p>流程（事务内，方法返回时提交）：
     * <ol>
     *   <li>清空该文件遗留的标签关联——上次失败尝试若已在 {@code finishProcessing} 落过部分
     *       标签（同事务提交），重跑生成同名标签会撞 (file_id, tag_id) 唯一约束，必须先清干净；</li>
     *   <li>删除旧 AsyncTask（按旧 taskId）；</li>
     *   <li>新建 UUID taskId + PENDING/0 任务；</li>
     *   <li>文件 {@code taskId=新}、{@code status=PENDING}、{@code summary=null} 落库。</li>
     * </ol>
     * storagePath 不变，异步管道从原路径重读重解析。
     *
     * @param id 文件 ID
     * @return 新任务 ID（供调用方在事务外触发异步管道）
     * @throws IllegalArgumentException 文件不存在或状态非 FAILED
     */
    @Transactional
    public String retryFile(Long id) {
        FileMetadata fm = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        if (fm.getStatus() != FileStatus.FAILED) {
            throw new IllegalArgumentException("仅失败文件可重试");
        }

        // 1) 清空上次失败尝试遗留的标签关联，避免重跑撞唯一约束
        fileTagMappingRepository.deleteAll(fileTagMappingRepository.findByFileId(fm.getId()));

        // 2) 删除旧任务
        String oldTaskId = fm.getTaskId();
        // 3) 删除旧任务并登记新 PENDING 任务
        String newTaskId = UUID.randomUUID().toString();
        taskRegistrationService.replaceWithPendingTask(oldTaskId, newTaskId, fm.getFileName());

        // 4) 文件回 PENDING 并清旧摘要，事务提交后由调用方异步重新解析
        fm.setTaskId(newTaskId);
        fm.setStatus(FileStatus.PENDING);
        fm.setSummary(null);
        fm.setModelSnapshotId(com.coffer.model.runtime.ModelExecutionContext.currentId());
        fm.setRevision(fm.getRevision() + 1);
        fm.setVectorIndexedAt(null);
        fileMetadataRepository.save(fm);

        log.info("文件重试登记完成 fileId={}, oldTaskId={}, newTaskId={}", id, oldTaskId, newTaskId);
        return newTaskId;
    }

    /**
     * 删除文件（允许任意状态）：事务内清理标签关联、关联任务与文件元数据，返回对象存储路径
     * 供调用方在事务提交后删除 MinIO 对象。
     *
     * <p>删除顺序：FileTagMapping → AsyncTask（按 taskId）→ FileMetadata。MinIO 对象删除
     * 不放本事务内——若事务回滚则对象先被删造成不一致；DB 删成功后对象删除失败仅留孤儿，
     * 与「DB 为准」哲学一致。正在跑的 PENDING/PROCESSING 异步线程随后各查询为空即自然退出。
     *
     * @param id 文件 ID
     * @return 文件的对象存储路径（storagePath，可空；Controller 依此决定是否删 MinIO 对象）
     * @throws IllegalArgumentException 文件不存在
     */
    @Transactional
    public String deleteFile(Long id) {
        FileMetadata fm = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        String storagePath = fm.getStoragePath();
        String taskId = fm.getTaskId();

        // Persist the cleanup intent in the same transaction as the DB deletion.
        // Redis is deliberately not required for user-visible deletion.
        vectorCleanupService.enqueue(id);
        storageDeletionTaskService.enqueue(id, storagePath);

        // 1) 删文件-标签关联
        fileTagMappingRepository.deleteAll(fileTagMappingRepository.findByFileId(fm.getId()));

        // 2) 删关联的异步任务（PENDING/PROCESSING 中被删，运行线程随后自然退出）
        taskRegistrationService.deleteTaskIfPresent(taskId);

        // 3) 删文件元数据
        fileMetadataRepository.delete(fm);

        log.info("文件删除登记完成 fileId={}, taskId={}", id, taskId);
        return storagePath;
    }

    /**
     * 文件重命名（任意状态均可）：仅改 {@code fileName}，不动分类/归档/标签/摘要。
     *
     * <p>同名字时幂等直接返回；新名去首尾空白后校验非空且 ≤255 字符。
     *
     * @param id      文件 ID
     * @param newName 新文件名（去空白后校验，≤255 字符）
     * @return 文件 ID
     * @throws IllegalArgumentException 文件名非法或文件不存在
     */
    @Transactional
    public Long renameFile(Long id, String newName) {
        if (newName == null || newName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalized = newName.trim();
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("文件名不能超过255个字符");
        }

        FileMetadata fm = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());

        // 同名幂等：不做无谓更新直接返回
        if (normalized.equals(fm.getFileName())) {
            log.info("文件重命名幂等跳过 fileId={}，名称未变化", id);
            return fm.getId();
        }

        fm.setFileName(normalized);
        fm.setRevision(fm.getRevision() + 1);
        fm.setVectorIndexedAt(null);
        fileMetadataRepository.save(fm);
        log.info("文件重命名完成 fileId={}", id);
        return fm.getId();
    }

}
