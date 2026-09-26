package com.coffer.file.application;

import com.coffer.file.api.dto.CategoryCountResponse;
import com.coffer.file.api.dto.FileDetailResponse;
import com.coffer.file.api.dto.FileListResponse;
import com.coffer.file.application.assembler.FileResponseAssembler;
import com.coffer.tag.api.dto.FileTagInfo;
import com.coffer.dto.Result;
import com.coffer.file.domain.CategoryType;
import com.coffer.tag.domain.ConfirmationStatus;
import com.coffer.file.domain.FileMetadata;
import com.coffer.tag.domain.FileTagMapping;
import com.coffer.tag.domain.Tag;
import com.coffer.file.infrastructure.persistence.FileMetadataRepository;
import com.coffer.tag.infrastructure.persistence.FileTagMappingRepository;
import com.coffer.tag.infrastructure.persistence.TagRepository;
import com.coffer.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 文件列表/查询服务：支持分页、按文件名或标签模糊搜索，并汇总标签确认状态。
 */
@Slf4j
@com.coffer.auth.service.OwnerOnly
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileMetadataRepository fileMetadataRepository;
    private final FileTagMappingRepository fileTagMappingRepository;
    private final TagRepository tagRepository;
    private final MinioStorageService minioStorageService;
    private final FileResponseAssembler fileResponseAssembler;

    /**
     * 兼容便捷入口：仅按关键词查询（分类不限、默认排序）。
     *
     * @param keyword  搜索关键词，为空则查询全部
     * @param pageable 分页参数
     * @return 文件列表分页结果
     */
    public Page<FileListResponse> listFiles(String keyword, Pageable pageable) {
        return listFiles(keyword, null, null, null, pageable);
    }

    /**
     * 分页查询文件列表：可选关键词（文件名 / AI 摘要 / 未拒绝标签）、可选分类过滤、可选标签筛选、白名单排序。
     *
     * <p>关键词 / 分类 / 标签均可选：都不给则查全部；关键词匹配扩到文件名与摘要。
     * tag 用于「点选标签芯片」的精确筛选（子串匹配未拒绝标签，与 {@code searchFiles} 语义一致）；
     * tag 与 category 同时出现时以 tag 为准（分类已从 UI 下线，仅内部保留）。
     * sortToken 取 {@code new|old|size|name} 四种语义（默认 new），在服务端重建 Pageable
     * 覆盖前端分页，保证跨页排序一致。为每个文件计算标签汇总确认状态并写入
     * {@link FileListResponse#getTagStatus()}，当前页标签关联批量加载避免 N+1。
     *
     * @param keyword   搜索关键词，为空则不约束
     * @param category  分类过滤（枚举名），为空则不约束（tag 非空时忽略）
     * @param tag       标签名（匹配已确认/待确认标签），为空则不约束
     * @param sortToken 排序语义 token，非法值抛 {@link IllegalArgumentException}
     * @param pageable  分页参数（page/size；排序由 sortToken 覆盖）
     * @return 文件列表分页结果
     * @throws IllegalArgumentException 排序 token 或分类参数非法时抛出
     */
    public Page<FileListResponse> listFiles(String keyword, String category, String tag, String sortToken, Pageable pageable) {
        // 1) 排序 token：空取默认 new，非法抛 400（由 Controller 捕获）
        Pageable rebuilt = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), parseSort(sortToken));

        // 2) 分类：空不约束；非空用枚举名严格解析，非法抛 400
        CategoryType cat = null;
        if (category != null && !category.isBlank()) {
            String normalized = category.trim();
            try {
                cat = CategoryType.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("非法分类参数: " + normalized);
            }
        }

        // 3) 关键词 / 标签归一化（去空白）后按有无选择查询路径
        String kw = keyword == null ? "" : keyword.trim();
        String tg = tag == null ? "" : tag.trim();
        Page<FileMetadata> page;
        if (!tg.isEmpty()) {
            // 标签筛选优先：文件名/摘要关键词 AND 未拒绝标签子串，排序随 rebuilt 生效
            page = fileMetadataRepository.searchFiles(kw, tg, rebuilt);
        } else if (!kw.isEmpty()) {
            page = fileMetadataRepository.searchByKeywordAndCategory(kw, cat, rebuilt);
        } else if (cat != null) {
            page = fileMetadataRepository.findByCategory(cat, rebuilt);
        } else {
            page = fileMetadataRepository.findAll(rebuilt);
        }

        Map<Long, List<FileTagMapping>> mappingsByFileId = loadMappings(page.getContent());
        Map<Long, String> tagNameById = resolvePageTagNames(mappingsByFileId);

        return page.map(fm -> toListResponse(fm,
                mappingsByFileId.getOrDefault(fm.getId(), List.of()), tagNameById));
    }

    /**
     * 将排序 token 解析为 Spring Data {@link Sort}。
     *
     * @param sortToken 排序 token（new/old/size/name）；null 或空白视为 new
     * @return 对应排序规则
     * @throws IllegalArgumentException token 不在白名单时抛出
     */
    private Sort parseSort(String sortToken) {
        String token = (sortToken == null || sortToken.isBlank()) ? "new" : sortToken.trim();
        return switch (token) {
            case "new" -> Sort.by(Sort.Direction.DESC, "uploadTime");
            case "old" -> Sort.by(Sort.Direction.ASC, "uploadTime");
            case "size" -> Sort.by(Sort.Direction.DESC, "fileSize");
            case "name" -> Sort.by(Sort.Direction.ASC, "fileName");
            default -> throw new IllegalArgumentException("非法排序参数: " + sortToken);
        };
    }

    /**
     * 分类计数：按分类分组统计文件数量（仅返回数量大于 0 的分类，按数量倒序）。
     *
     * @return 成功响应，data 为分类计数列表（分类枚举名 + 数量）
     */
    public Result<List<CategoryCountResponse>> listCategories() {
        List<FileMetadataRepository.CategoryCount> counts = fileMetadataRepository.countByCategory();
        if (counts == null || counts.isEmpty()) {
            return Result.success(List.of());
        }
        List<CategoryCountResponse> list = counts.stream()
                .map(cc -> CategoryCountResponse.builder()
                        .category(cc.getCategory() == null ? null : cc.getCategory().name())
                        .count(cc.getCount())
                        .build())
                .toList();
        return Result.success(list);
    }

    /**
     * 组合搜索：关键词匹配文件名，标签匹配已确认标签名，两个条件均为可选（AND 交集）。
     *
     * @param keyword  文件名关键词，可选
     * @param tag      标签名关键词（仅匹配已确认标签），可选
     * @param pageable 分页参数
     * @return 匹配的文件列表分页结果
     */
    public Page<FileListResponse> searchFiles(String keyword, String tag, Pageable pageable) {
        // JPQL 中以空串表达"该维度不约束"，此处统一归一化避免 null 参与比较
        String kw = keyword == null ? "" : keyword.trim();
        String tg = tag == null ? "" : tag.trim();
        Page<FileMetadata> page = fileMetadataRepository.searchFiles(kw, tg, pageable);

        Map<Long, List<FileTagMapping>> mappingsByFileId = loadMappings(page.getContent());
        Map<Long, String> tagNameById = resolvePageTagNames(mappingsByFileId);

        return page.map(fm -> toListResponse(fm,
                mappingsByFileId.getOrDefault(fm.getId(), List.of()), tagNameById));
    }

    /**
     * 查询文件详情：基础信息 + 已确认/待确认标签列表 + 标签汇总状态 + 临时预览 URL。
     *
     * <p>标签按确认状态拆分为两组（已拒绝的标签视为作废，不返回）；预览地址指向
     * 登录会话保护的文件代理端点，不签发可转发的对象存储链接。
     *
     * @param id 文件 ID
     * @return 文件详情响应
     * @throws IllegalArgumentException 文件不存在
     */
    public FileDetailResponse getFileDetail(Long id) {
        FileMetadata fm = fileMetadataRepository.findById(id)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
        List<FileTagMapping> mappings = fileTagMappingRepository.findByFileId(id);

        Map<Long, String> tagNameById = resolveTagNames(mappings);
        List<FileTagInfo> confirmedTags = toTagInfos(mappings, tagNameById,
                m -> m.getConfirmationStatus() == ConfirmationStatus.CONFIRMED);
        List<FileTagInfo> pendingTags = toTagInfos(mappings, tagNameById,
                m -> m.getConfirmationStatus() == ConfirmationStatus.PENDING_CONFIRMATION);

        String previewUrl = fm.getStoragePath() == null || fm.getStoragePath().isBlank()
                ? null : "/api/files/" + fm.getId() + "/content";

        return fileResponseAssembler.toDetailResponse(fm, computeTagStatus(mappings),
                confirmedTags, pendingTags, previewUrl);
    }

    /** Resolve content only after the owner-filtered metadata lookup succeeds. */
    public FileMetadata requireFileForContent(Long id) {
        return fileMetadataRepository.findById(id)
                .orElseThrow(() -> new com.coffer.auth.service.ResourceNotFoundException());
    }

    /**
     * 按确认状态过滤标签关联并转为 {@link FileTagInfo}。
     */
    private List<FileTagInfo> toTagInfos(List<FileTagMapping> mappings, Map<Long, String> tagNameById,
                                         Predicate<FileTagMapping> filter) {
        return mappings.stream()
                .filter(filter)
                .map(m -> FileTagInfo.builder()
                        .tagId(m.getTagId())
                        .tagName(tagNameById.get(m.getTagId()))
                        .confirmationStatus(m.getConfirmationStatus() == null
                                ? null : m.getConfirmationStatus().name())
                        .build())
                .toList();
    }

    /**
     * 批量解析标签 ID → 标签名映射。
     */
    private Map<Long, String> resolveTagNames(List<FileTagMapping> mappings) {
        List<Long> tagIds = mappings.stream().map(FileTagMapping::getTagId).distinct().toList();
        if (tagIds.isEmpty()) {
            return Map.of();
        }
        return tagRepository.findAllById(tagIds).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getTagName));
    }

    /**
     * 批量加载当前页所有文件的标签关联（含 PENDING_CONFIRMATION/CONFIRMED/REJECTED）。
     *
     * @param files 当前页文件列表
     * @return fileId → 该文件下全部标签关联
     */
    private Map<Long, List<FileTagMapping>> loadMappings(List<FileMetadata> files) {
        if (files.isEmpty()) {
            return Map.of();
        }
        List<Long> fileIds = files.stream().map(FileMetadata::getId).toList();
        return fileTagMappingRepository.findByFileIdIn(fileIds).stream()
                .collect(Collectors.groupingBy(FileTagMapping::getFileId));
    }

    /**
     * 批量解析当前页所有标签关联涉及的标签名（一次查询，避免逐文件 N+1）。
     *
     * @param mappingsByFileId fileId → 标签关联
     * @return tagId → 标签名
     */
    private Map<Long, String> resolvePageTagNames(Map<Long, List<FileTagMapping>> mappingsByFileId) {
        List<FileTagMapping> all = mappingsByFileId.values().stream()
                .flatMap(List::stream).toList();
        return resolveTagNames(all);
    }

    /**
     * 装配列表响应项：基础字段 + 非拒绝标签明细（含已确认与待确认，卡片直接可渲染）。
     *
     * @param fm          文件元数据
     * @param mappings    该文件全部标签关联
     * @param tagNameById 标签名映射（整页批量解析）
     * @return 文件列表响应项
     */
    private FileListResponse toListResponse(FileMetadata fm, List<FileTagMapping> mappings,
                                            Map<Long, String> tagNameById) {
        List<FileTagInfo> tags = toTagInfos(mappings, tagNameById,
                m -> m.getConfirmationStatus() != ConfirmationStatus.REJECTED);
        return fileResponseAssembler.toListResponse(fm, computeTagStatus(mappings), tags);
    }

    /**
     * 汇总某文件的标签确认状态，规则（按优先级）：
     * <ol>
     *   <li>无标签关联 → {@code NO_TAG}</li>
     *   <li>存在待确认（PENDING_CONFIRMATION）→ {@code PENDING}（需用户优先处理）</li>
     *   <li>全部已确认 → {@code ALL_CONFIRMED}</li>
     *   <li>全部已拒绝 → {@code ALL_REJECTED}</li>
     *   <li>混合状态（既有已确认又有已拒绝、无待确认）→ 按简化规则统一返回 {@code PENDING}</li>
     * </ol>
     *
     * @param mappings 该文件下的全部标签关联
     * @return 标签汇总确认状态
     */
    private String computeTagStatus(List<FileTagMapping> mappings) {
        if (mappings.isEmpty()) {
            return "NO_TAG";
        }
        if (mappings.stream().anyMatch(m -> m.getConfirmationStatus() == ConfirmationStatus.PENDING_CONFIRMATION)) {
            return "PENDING";
        }
        if (mappings.stream().allMatch(m -> m.getConfirmationStatus() == ConfirmationStatus.CONFIRMED)) {
            return "ALL_CONFIRMED";
        }
        if (mappings.stream().allMatch(m -> m.getConfirmationStatus() == ConfirmationStatus.REJECTED)) {
            return "ALL_REJECTED";
        }
        // 混合状态简化：统一按 PENDING 处理
        return "PENDING";
    }
}
