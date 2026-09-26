package com.coffer.file.application;

import com.coffer.tag.api.dto.TagAndCategoryResult;
import com.coffer.dto.VisionResult;
import com.coffer.task.domain.AsyncTaskStatus;
import com.coffer.task.application.AsyncTaskService;
import com.coffer.task.application.TaskRegistrationService;
import com.coffer.file.domain.CategoryType;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.file.application.parse.DocumentParseService;
import com.coffer.service.MinioStorageService;
import com.coffer.service.VisionModelService;
import com.coffer.tool.TagGenerationTool;
import com.coffer.file.domain.parse.ParseResult;
import com.coffer.file.domain.parse.ParseStatus;
import com.coffer.vector.VectorIndexingService;
import com.coffer.vector.FileVectorIndexRequested;
import com.coffer.governance.domain.GovernanceRunMode;
import com.coffer.model.runtime.ModelRuntimeModeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 上传自动分析管道：以 {@code taskId} 为统一入口，编排「文件解析 → 标签生成 → 结果入库」
 * 的顺序执行工作流。
 *
 * <p>触发后按文件类型分支：
 * <ul>
 *   <li><b>文本文件</b>（txt/pdf/docx/doc）：从 MinIO 读取文件流 → 解析提取纯文本 →
 *       调用 LLM 生成受控分类与关键词标签。</li>
 *   <li><b>图片文件</b>（jpg/jpeg/png/gif/webp/bmp）：跳过文本解析，读取字节 →
 *       Base64 编码 → 调用视觉模型（Qwen-VL）一次产出受控分类 + 标签 + 图片描述。</li>
 * </ul>
 * 两分支汇合到 {@link #finishProcessing}：标签去重入库并建立文件-标签关联
 * （PENDING 待人工确认）→ category 落库 → 摘要写入文件元数据 → 更新文件与任务状态为完成。
 * 任一环节异常则文件与任务置为 FAILED，并写入不含底层异常详情的安全错误摘要供前端轮询展示。
 *
 * <p>文件经 {@code FileMetadata.taskId = taskId} 与任务关联定位（AsyncTask 无 fileMetadataId 字段）。
 * 方法标注 {@code @Async("taskExecutor")} 异步执行保证上传接口快速返回，
 * 同方法标注 {@code @Transactional}：异常在方法内被捕获并回写失败状态后提交，
 * 保证无论成功或失败任务终态均被持久化，且异常不向外抛出。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class UploadPipelineService {

    /** 摘要最大长度，解析文本超长时截断后存入 summary。 */
    private static final int SUMMARY_MAX_LENGTH = 1000;

    /** 图片软上限（字节）：超过直接 FAILED。Phase 1 不做压缩/缩放，留待后续（ImageContent.DetailLevel 已可用）。 */
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024;

    /** 走视觉模型识别的图片扩展名（GIF 按图片处理，只看首帧语义）。 */
    private static final List<String> IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    private final MinioStorageService minioStorageService;
    private final DocumentParseService documentParseService;
    private final TagGenerationTool tagGenerationTool;
    private final VisionModelService visionModelService;
    private final FileMetadataRepository fileMetadataRepository;
    private final TagRepository tagRepository;
    private final FileTagMappingRepository fileTagMappingRepository;
    private final TaskRegistrationService taskRegistrationService;
    private final AsyncTaskService asyncTaskService;
    private final VectorIndexingService vectorIndexingService;

    /** Optional for focused unit tests that construct this service without Spring. */
    @Autowired(required = false)
    private ModelRuntimeModeService runtimeModeService;
    @Autowired(required = false)
    private com.coffer.model.runtime.ModelExecutionSnapshotService modelSnapshots;

    /** Field injection preserves the existing small Mockito constructor used by unit tests. */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    /**
     * 登记上传任务（事务内同步落库元数据与任务记录）。
     *
     * <p>由 Controller 上传接口在同步上传 MinIO 后调用：保存 FileMetadata（PENDING，
     * {@code taskId} 回填关联）与 AsyncTask（PENDING/0）两条记录，事务提交后再由调用方
     * 异步触发 {@link #processUploadPipeline}。抽离为独立事务方法而非放在 Controller：
     * 若在 Controller 加 {@code @Transactional} 并在事务内触发异步，异步线程会读到
     * 未提交的数据导致任务立即失败。
     *
     * @param taskId           异步任务 ID
     * @param originalFilename 原始文件名
     * @param fileType         文件扩展名
     * @param storagePath      对象存储路径（文件已上传到 MinIO）
     * @param fileSize         文件大小（字节）
     * @return 已保存的文件元数据（含自增 ID 与上传时间，供构建响应）
     */
    @Transactional
    public FileMetadata registerUploadTask(String taskId, String originalFilename, String fileType,
                                           String storagePath, Long fileSize) {
        FileMetadata metadata = FileMetadata.builder()
                .fileName(originalFilename)
                .fileSize(fileSize)
                .fileType(fileType)
                .storagePath(storagePath)
                .taskId(taskId)
                .modelSnapshotId(com.coffer.model.runtime.ModelExecutionContext.currentId())
                .build();
        fileMetadataRepository.save(metadata);

        // AsyncTask 无 fileMetadataId 字段，文件与任务经 FileMetadata.taskId 关联
        taskRegistrationService.createPendingTask(taskId, originalFilename);
        log.info("上传任务登记完成");
        return metadata;
    }

    /**
     * 上传自动分析管道统一入口（异步执行）。
     *
     * @param taskId 异步任务 ID（同时作为 FileMetadata.taskId 关联定位文件）
     */
    @Transactional
    public void processUploadPipeline(String taskId) {
        if (modelSnapshots != null) {
            String id = taskRegistrationService.findSnapshotId(taskId);
            if (id == null) {
                asyncTaskService.markAsFailed(taskId, "任务缺少模型目标确认，请确认后重试");
                fileMetadataRepository.findByTaskId(taskId).ifPresent(file -> { file.markAsFailed(); fileMetadataRepository.save(file); });
                return;
            }
            modelSnapshots.with(id, () -> processUploadPipelineInternal(taskId));
            return;
        }
        java.util.Optional<GovernanceRunMode> taskMode = taskRegistrationService.findRunMode(taskId);
        if (runtimeModeService != null && taskMode != null && taskMode.isPresent()) {
            runtimeModeService.withSnapshot(taskMode.get(), () -> processUploadPipelineInternal(taskId));
            return;
        }
        processUploadPipelineInternal(taskId);
    }

    private void processUploadPipelineInternal(String taskId) {
        try {
            // 1. 定位异步任务，不存在则抛出 IllegalArgumentException（由外层 catch 统一处理）
            taskRegistrationService.requireExistingTask(taskId);

            // 2. 经 FileMetadata.taskId 定位文件元数据，不存在则任务置 FAILED 并返回
            FileMetadata metadata = fileMetadataRepository.findByTaskId(taskId)
                    .orElse(null);
            if (metadata == null) {
                asyncTaskService.markAsFailed(taskId, "文件元数据不存在");
                log.error("文件处理管道失败：文件元数据不存在");
                return;
            }

            // 3. 任务与文件置为处理中（进度 10）
            asyncTaskService.updateTaskStatus(taskId, AsyncTaskStatus.PROCESSING, 10, null);
            metadata.markAsProcessing();
            fileMetadataRepository.save(metadata);
            log.info("文件处理管道启动");

            // 4. 按文件类型分支：图片走视觉模型，其余保持文本解析路径
            if (isImage(metadata.getFileType())) {
                processImage(metadata, taskId);
            } else {
                processText(metadata, taskId);
            }
        } catch (Exception e) {
            String safeFailure = safeFailureMessage(e);
            log.error("文件处理管道执行失败，异常类型={}", e.getClass().getSimpleName());
            try {
                asyncTaskService.markAsFailed(taskId, safeFailure);
                FileMetadata failedMetadata = fileMetadataRepository.findByTaskId(taskId).orElse(null);
                if (failedMetadata != null) {
                    failedMetadata.markAsFailed();
                    fileMetadataRepository.save(failedMetadata);
                }
                log.warn("文件处理管道失败状态已回写");
            } catch (Exception saveError) {
                log.error("失败状态回写失败，异常类型={}", saveError.getClass().getSimpleName());
            }
        }
    }

    /**
     * 文本解析路径：从 MinIO 读取文件流解析纯文本 → LLM 生成受控分类 + 标签 → 汇合 finishProcessing。
     *
     * @param metadata 文件元数据（fileName/storagePath 已就绪）
     * @param taskId   异步任务 ID
     * @throws java.io.IOException 读取 MinIO 文件流失败（由调用方 {@code processUploadPipeline} 外层 catch 统一处理）
     */
    private void processText(FileMetadata metadata, String taskId) throws java.io.IOException {
        // 从 MinIO 读取文件流并解析纯文本（bucket 传 null 使用配置默认桶）
        String text;
        try (InputStream inputStream = minioStorageService.getFileStream(null, metadata.getStoragePath())) {
            ParseResult parseResult = documentParseService.extractTextFromFile(metadata.getFileName(), inputStream);
            if (parseResult.getStatus() != ParseStatus.SUCCESS) {
                throw new IllegalStateException("文件解析失败: " + parseResult.getStatus()
                        + (parseResult.getErrorMessage() != null ? ", " + parseResult.getErrorMessage() : ""));
            }
            text = parseResult.getContent();
        }
        log.info("文件解析完成，文本长度 {} 字符", text == null ? 0 : text.length());

        // 基于文本生成受控分类 + 关键词标签（进度 50），一次调用同时产出 category 与 tags
        asyncTaskService.updateTaskStatus(taskId, AsyncTaskStatus.PROCESSING, 50, null);
        TagAndCategoryResult tagResult = tagGenerationTool.generateTagAndCategory(text);
        List<String> tagNames = tagResult.tags();
        log.info("标签与分类生成完成，分类={}, 标签 {} 个",
                tagResult.category(), tagNames.size());

        finishProcessing(metadata, tagResult.category(), tagNames, buildSummary(text), taskId);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FileVectorIndexRequested(metadata.getId()));
        } else {
            // Direct unit invocations have no Spring transaction event publisher.
            // Keep the legacy path only for that non-production test harness.
            if (vectorIndexingService.indexParsedText(metadata, text)) fileMetadataRepository.save(metadata);
        }
    }

    /**
     * 图片识别路径：读取图片字节（超 10MB 软上限直接失败）→ Base64 编码 →
     * 视觉模型一次产出受控分类 + 标签 + 描述 → 汇合 finishProcessing（描述即摘要）。
     *
     * @param metadata 文件元数据（fileName/storagePath/fileType 已就绪）
     * @param taskId   异步任务 ID
     * @throws java.io.IOException 读取 MinIO 文件流失败（由调用方 {@code processUploadPipeline} 外层 catch 统一处理）
     */
    private void processImage(FileMetadata metadata, String taskId) throws java.io.IOException {
        asyncTaskService.updateTaskStatus(taskId, AsyncTaskStatus.PROCESSING, 30, null);
        Long reportedSize = metadata.getFileSize();
        if (reportedSize != null && reportedSize > MAX_IMAGE_SIZE_BYTES) {
            throw new SafeProcessingException("图片过大，超过 10MB 处理上限");
        }

        byte[] imageBytes;
        try (InputStream inputStream = minioStorageService.getFileStream(null, metadata.getStoragePath())) {
            imageBytes = inputStream.readAllBytes();
        }
        if (imageBytes.length > MAX_IMAGE_SIZE_BYTES) {
            throw new SafeProcessingException("图片过大，超过 10MB 处理上限");
        }

        asyncTaskService.updateTaskStatus(taskId, AsyncTaskStatus.PROCESSING, 60, null);
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        VisionResult visionResult = visionModelService.describeImage(
                base64, imageMime(metadata.getFileType()), metadata.getFileName());
        log.info("图片识别完成，分类={}, 标签 {} 个",
                visionResult.category(), visionResult.tags().size());

        String summary = visionResult.description() == null ? "" : visionResult.description();
        finishProcessing(metadata, visionResult.category(), visionResult.tags(), summary, taskId);
    }

    /**
     * 两分支汇合：标签去重入库（不存在则创建）并建立文件-标签关联（PENDING_CONFIRMATION 待人工确认）→
     * category 落库 → 摘要写入文件元数据 → 文件与任务置为完成（进度 100）。
     *
     * @param metadata 文件元数据（category/summary/status 在此写入并保存）
     * @param category 受控分类（图片来自视觉模型，文本来自 TagGenerationTool）
     * @param tagNames 关键词标签列表（空则抛异常置失败）
     * @param summary  摘要（图片为视觉描述，文本为截断文本）
     * @param taskId   异步任务 ID
     */
    private void finishProcessing(FileMetadata metadata, CategoryType category, List<String> tagNames,
                                  String summary, String taskId) {
        if (tagNames == null || tagNames.isEmpty()) {
            throw new SafeProcessingException("标签生成失败，无有效标签");
        }
        metadata.setCategory(category);

        List<FileTagMapping> mappings = new ArrayList<>();
        for (String tagName : tagNames) {
            Tag tag = tagRepository.findByTagName(tagName)
                    .orElseGet(() -> tagRepository.save(Tag.builder().tagName(tagName).build()));
            FileTagMapping mapping = FileTagMapping.builder()
                    .fileId(metadata.getId())
                    .tagId(tag.getId())
                    .build();
            // 系统自动生成的标签默认待人工确认，显式设置（与 @Builder.Default 双保险）
            mapping.setConfirmationStatus(ConfirmationStatus.PENDING_CONFIRMATION);
            mappings.add(mapping);
        }
        fileTagMappingRepository.saveAll(mappings);
        log.info("文件标签关联入库完成，关联 {} 条", mappings.size());

        metadata.markAsCompleted(summary);
        fileMetadataRepository.save(metadata);
        asyncTaskService.markAsCompleted(taskId, summary);
        log.info("文件处理管道完成");
    }

    /**
     * 判断扩展名是否为图片（GIF 按图片处理，只看首帧语义）。大小写不敏感。
     *
     * @param fileType 文件扩展名（无点号）
     * @return true 为图片扩展名
     */
    private static boolean isImage(String fileType) {
        return fileType != null && IMAGE_EXTENSIONS.contains(fileType.toLowerCase(Locale.ROOT));
    }

    /**
     * 由扩展名推导图片 MIME 类型（不新增 DB 列；若后续需要精确 MIME 再在 registerUploadTask 捕获落库）。
     *
     * @param fileType 文件扩展名（无点号）
     * @return 图片 MIME 类型
     */
    private static String imageMime(String fileType) {
        if (fileType == null) {
            return "image/png";
        }
        return switch (fileType.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> "image/png";
        };
    }

    /**
     * 将解析文本截断为摘要。
     */
    private String buildSummary(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SUMMARY_MAX_LENGTH ? text : text.substring(0, SUMMARY_MAX_LENGTH);
    }

    /** Returns only an allowlisted, non-sensitive reason for display in task status. */
    private static String safeFailureMessage(Exception error) {
        if (error instanceof SafeProcessingException safeError) {
            return safeError.safeMessage;
        }
        return "文件处理失败，请稍后重试或联系管理员";
    }

    /** A deliberate, pre-approved user-facing failure reason with no exception detail attached. */
    private static final class SafeProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String safeMessage;

        private SafeProcessingException(String safeMessage) {
            this.safeMessage = safeMessage;
        }
    }
}
