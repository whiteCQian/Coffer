package com.coffer.controller;

import com.coffer.file.api.dto.FileListResponse;
import com.coffer.dto.Result;
import com.coffer.file.application.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件搜索接口：支持文件名关键词 + 已确认标签关键词的组合搜索。
 *
 * <p>路径 {@code /api/files/search} 为字面量，优先于 {@code /api/files/{id}} 匹配。
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class SearchController {

    private final FileService fileService;

    /**
     * 组合搜索：keyword 匹配文件名，tag 匹配已确认标签名，二者可选（同时给出取交集）。
     *
     * @param keyword  文件名关键词，可选，为空则不限文件名
     * @param tag      标签名关键词（仅匹配已确认标签），可选，为空则不限标签
     * @param pageable 分页参数（默认每页 10 条）
     * @return 统一响应，data 为分页文件列表（含 tagStatus 标签汇总确认状态）
     */
    @GetMapping("/search")
    public Result<Page<FileListResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @PageableDefault(size = 10) Pageable pageable) {
        log.info("执行文件搜索 page={}, size={}", pageable.getPageNumber(), pageable.getPageSize());
        return Result.success(fileService.searchFiles(keyword, tag, pageable));
    }
}
