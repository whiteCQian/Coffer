package com.coffer.tag.api;

import com.coffer.tag.api.dto.ConfirmTagRequest;
import com.coffer.tag.api.dto.RejectTagRequest;
import com.coffer.dto.Result;
import com.coffer.tag.api.dto.TagCandidateResponse;
import com.coffer.tag.application.TagConfirmationService;
import com.coffer.tag.application.TagSuggestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件标签确认/拒绝与标签候选池接口。
 */
@Slf4j
@RestController
@RequestMapping("/api/files/tags")
@RequiredArgsConstructor
public class FileTagController {

    private final TagConfirmationService tagConfirmationService;
    private final TagSuggestionService tagSuggestionService;

    /**
     * 确认文件标签关联。
     *
     * @param request 请求体（fileId、tagId 均不能为空）
     * @return 统一响应；成功 code=0，业务/参数异常 code=400，其他异常 code=500
     */
    @PostMapping("/confirm")
    public Result<Void> confirmTag(@RequestBody @Valid ConfirmTagRequest request) {
        try {
            tagConfirmationService.confirmTag(request.getFileId(), request.getTagId());
            log.info("标签确认成功 fileId={}, tagId={}", request.getFileId(), request.getTagId());
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 业务异常（关联不存在、参数非法）：返回 400 + 明确错误信息
            log.warn("标签确认业务异常");
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("确认标签失败，异常类型={}", e.getClass().getSimpleName());
            return Result.error(500, "确认标签失败");
        }
    }

    /**
     * 拒绝文件标签关联，可附带修正标签（newTagName 可为空，为空则仅拒绝）。
     *
     * @param request 请求体（fileId、tagId 不能为空，newTagName 可选）
     * @return 统一响应；成功 code=0，业务/参数异常 code=400，其他异常 code=500
     */
    @PostMapping("/reject")
    public Result<Void> rejectTag(@RequestBody @Valid RejectTagRequest request) {
        try {
            tagConfirmationService.rejectTag(request.getFileId(), request.getTagId(), request.getNewTagName());
            log.info("标签拒绝成功 fileId={}, tagId={}", request.getFileId(), request.getTagId());
            return Result.success();
        } catch (IllegalArgumentException e) {
            // 业务异常（关联不存在、参数非法）：返回 400 + 明确错误信息
            log.warn("拒绝标签业务异常");
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("拒绝标签失败，异常类型={}", e.getClass().getSimpleName());
            return Result.error(500, "拒绝标签失败");
        }
    }

    /**
     * 标签候选池：返回被文件确认采用过的标签名及覆盖文件数，按覆盖数倒序。
     *
     * <p>无入参；仅统计 CONFIRMED 关联并按文件数去重（见
     * 标签应用服务聚合，不涉及确认/拒绝生命周期。
     *
     * @return 统一响应，data 为标签候选列表（含空列表，无则返回空）
     */
    @GetMapping("/candidates")
    public Result<List<TagCandidateResponse>> getTagCandidates() {
        List<TagCandidateResponse> candidates = tagSuggestionService.listCandidates();
        log.info("标签候选池查询完成，共 {} 个候选标签", candidates.size());
        return Result.success(candidates);
    }

    /**
     * 标签联想：根据自然语言描述返回库中真实存在的候选标签及覆盖文件数。
     *
     * <p>输入 query 为空时返回空列表；字面命中直接返回（不调模型），字面未命中由
     * {@link TagSuggestionService} 走 DeepSeek 语义联想；任何失败均返回空列表，调用方据此降级。
     *
     * @param q 用户输入的自然语言描述（如"上次出差的发票"）
     * @return 统一响应，data 为联想出的真实标签列表（可为空）
     */
    @GetMapping("/suggest")
    public Result<List<TagCandidateResponse>> suggestTags(@RequestParam String q) {
        return Result.success(tagSuggestionService.suggestLocal(q));
    }

    @PostMapping("/suggest")
    @com.coffer.model.runtime.ModelSubmission("TAG_SUGGEST")
    public Result<List<TagCandidateResponse>> suggestTagsWithModel(@RequestBody SuggestRequest request) {
        String q = request.query();
        List<TagCandidateResponse> suggestions = tagSuggestionService.suggest(q);
        log.info("标签联想完成，共 {} 个建议", suggestions.size());
        return Result.success(suggestions);
    }

    public record SuggestRequest(String query) {}
}
