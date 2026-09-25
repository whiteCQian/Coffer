/* ============================================================
   API 类型定义

   后端 DTO 的原始契约由 ./generated/schema.ts 生成。这里保留稳定的
   前端别名和 UI 专用类型，避免生成器字段命名变化扩散到组件层。
   运行 `npm run api:generate` 更新原始契约。
   ============================================================ */

import type { components } from './generated'

export type ApiSchema = components['schemas']
type RequiredSchema<T> = T extends readonly (infer U)[]
  ? RequiredSchema<U>[]
  : T extends object
    ? { [K in keyof T]-?: RequiredSchema<T[K]> }
    : T

/** 统一 API 响应封装 */
export interface Result<T = unknown> {
  code: number
  msg: string
  data: T
}

export type ModelProvider = 'DEEPSEEK' | 'QWEN_VL'

export type ModelCredentialStatusResponse = RequiredSchema<ApiSchema['ModelCredentialStatusResponse']>

export type ModelCredentialTestResponse = RequiredSchema<ApiSchema['ModelCredentialTestResponse']>

/** Spring Data 分页结构（GET /api/files 的 data 形态） */
export type Page<T> = Omit<RequiredSchema<ApiSchema['PageFileListResponse']>, 'content'> & {
  content: T[]
}

/** 文件处理状态 */
export type FileStatus = NonNullable<ApiSchema['FileListResponse']['status']>

/** 文件处理状态（AsyncTask 独立枚举，语义相同） */
export type AsyncTaskStatus = NonNullable<ApiSchema['TaskProgressResponse']['status']>

/** 标签汇总确认状态 */
export type TagStatus = NonNullable<ApiSchema['FileListResponse']['tagStatus']>

/** 单个标签确认状态 */
export type ConfirmationStatus = NonNullable<ApiSchema['FileTagInfo']['confirmationStatus']>

/** 受控分类词表 */
export type Category = NonNullable<ApiSchema['FileListResponse']['category']>

/** 文件列表项：基础字段 + 卡片渲染所需展示字段（category/status/archived/非拒标签） */
export type FileListResponse = RequiredSchema<ApiSchema['FileListResponse']>

/** 文件详情中的单个标签 */
export type FileTagInfo = RequiredSchema<ApiSchema['FileTagInfo']>

/** 文件详情 */
export type FileDetailResponse = RequiredSchema<ApiSchema['FileDetailResponse']>

/** 文件上传响应（taskId 用于轮询进度） */
export type FileUploadResponse = RequiredSchema<ApiSchema['FileUploadResponse']>

/** 异步任务进度快照 */
export type TaskProgressResponse = RequiredSchema<ApiSchema['TaskProgressResponse']>

/** 首页总览「处理中」明细项 */
export type ProcessingTaskItem = RequiredSchema<ApiSchema['ProcessingTaskItem']>

/** 首页总览「待确认标签文件」明细项 */
export type PendingConfirmItem = RequiredSchema<ApiSchema['PendingConfirmItem']>

/** 首页总览「失败任务」明细项 */
export type FailedTaskItem = RequiredSchema<ApiSchema['FailedTaskItem']>

/** 首页总览（GET /api/tasks/overview 的 data 形态）：处理中/失败/待确认三类面板 */
export type TaskOverviewResponse = RequiredSchema<ApiSchema['TaskOverviewResponse']>

/** 收件箱批量导入明细（由后端 OpenAPI 契约生成）。 */
export type InboxImportItemResponse = RequiredSchema<ApiSchema['InboxImportItemResponse']>

/** 收件箱批量导入进度（由后端 OpenAPI 契约生成）。 */
export type InboxImportProgressResponse = RequiredSchema<ApiSchema['InboxImportProgressResponse']>

/** Dry-run governance preview item. */
export type GovernancePreviewItemResponse = RequiredSchema<ApiSchema['GovernancePreviewItemResponse']>

/** C07 archive execution batch. */
export type ArchiveOperationBatchResponse = RequiredSchema<ApiSchema['ArchiveOperationBatchResponse']>

/** C08 archive ledger batch list row. */
export type ArchiveOperationBatchSummaryResponse = RequiredSchema<ApiSchema['ArchiveOperationBatchSummaryResponse']>

/** C07 archive execution item. */
export type ArchiveOperationItemResponse = RequiredSchema<ApiSchema['ArchiveOperationItemResponse']>

/** C10 durable archive/rollback compensation task. */
export type GovernanceCompensationTaskResponse = RequiredSchema<ApiSchema['GovernanceCompensationTaskResponse']>

/** Dry-run governance preview batch. */
export type GovernancePreviewResponse = RequiredSchema<ApiSchema['GovernancePreviewResponse']>

/** Dry-run preview creation request. */
export type CreateGovernancePreviewRequest = ApiSchema['CreateGovernancePreviewRequest']

/** C11 single/batch re-analysis request. */
export type ReanalyzeGovernanceFilesRequest = ApiSchema['ReanalyzeGovernanceFilesRequest']
export type ReanalyzeGovernanceFileRequest = ApiSchema['ReanalyzeGovernanceFileRequest']

/** Dry-run preview regeneration request. */
export type RegenerateGovernancePreviewRequest = ApiSchema['RegenerateGovernancePreviewRequest']

/** Edit one C06 preview suggestion. */
export type UpdateGovernancePreviewItemRequest = ApiSchema['UpdateGovernancePreviewItemRequest']

/** Confirm selected or all C06 preview items. */
export type ConfirmGovernancePreviewRequest = ApiSchema['ConfirmGovernancePreviewRequest']

/** Skip one C06 preview item. */
export type SkipGovernancePreviewItemRequest = ApiSchema['SkipGovernancePreviewItemRequest']

/** 对话请求 */
export type ChatRequest = ApiSchema['ChatRequest']

/** 对话回复的真实文件引用 */
export type ChatCitation = RequiredSchema<ApiSchema['ChatCitation']>

/** 对话响应 */
export type ChatQueryResponse = RequiredSchema<ApiSchema['ChatQueryResponse']>

/** 确认标签请求 */
export type ConfirmTagRequest = ApiSchema['ConfirmTagRequest']

/** 拒绝标签请求 */
export type RejectTagRequest = ApiSchema['RejectTagRequest']

/** 分类计数（GET /api/files/categories 的 data 元素） */
export type CategoryCountResponse = RequiredSchema<ApiSchema['CategoryCountResponse']>

/** 文件重命名请求（PATCH /api/files/{id}） */
export type RenameFileRequest = ApiSchema['RenameFileRequest']

/** 文件改分类请求（PUT /api/files/{id}/category） */
export type ChangeCategoryRequest = RequiredSchema<ApiSchema['ChangeCategoryRequest']>

/** 排序语义 token（服务端白名单映射） */
export type FileSortToken = 'new' | 'old' | 'size' | 'name'

/** 标签候选：库中真实存在、被文件采用过的标签及覆盖文件数（/files/tags/candidates、/suggest 的 data 元素） */
export type TagCandidate = RequiredSchema<ApiSchema['TagCandidateResponse']>
