import http from './http'
import type {
  Category,
  CategoryCountResponse,
  FileDetailResponse,
  FileListResponse,
  FileSortToken,
  FileUploadResponse,
  Page,
  RenameFileRequest,
  Result,
} from './types'

/** 文件列表查询参数（服务端分页/过滤/排序） */
export interface ListFilesParams {
  /** 关键词：匹配文件名 / AI 摘要 / 未拒绝（已确认+待确认）标签 */
  keyword?: string
  /** 分类过滤（受控枚举名），空则不约束（tag 非空时后端忽略） */
  category?: Category | null
  /** 精确标签筛选：点选「常用标签 / AI 联想」芯片后传标签名 */
  tag?: string
  /** 排序 token：new|old|size|name，缺省 new */
  sort?: FileSortToken
  /** 0-based 页码 */
  page?: number
  /** 每页条数（默认 10，Files 页固定 12） */
  size?: number
}

/** 文件列表：服务端关键词/分类/标签过滤 + 白名单排序 + 分页 */
export function listFiles(params: ListFilesParams = {}) {
  return http.get<Result<Page<FileListResponse>>>('/files', { params })
}

/** 文件详情：基础信息 + 已确认/待确认标签 + 预览 URL */
export function getFileDetail(id: number, revision?: number) {
  return http.get<Result<FileDetailResponse>>(`/files/${id}`, { params: { revision } })
}

/** 分类计数：count>0 的分类按数量倒序，供「全部文件」顶部 tab 徽标 */
export function listCategories() {
  return http.get<Result<CategoryCountResponse[]>>('/files/categories')
}

/** 组合搜索：keyword 匹配文件名/摘要，tag 匹配已确认/待确认（未拒绝）标签 */
export function searchFiles(params: { keyword?: string; tag?: string; page?: number; size?: number } = {}) {
  return http.get<Result<Page<FileListResponse>>>('/files/search', { params })
}

/** 文件重命名（返回更新后详情，前端直接刷新当前行/抽屉） */
export function renameFile(id: number, payload: RenameFileRequest) {
  return http.patch<Result<FileDetailResponse>>(`/files/${id}`, payload)
}

/** 删除文件：允许任意状态，级联清理标签/任务/MinIO 对象 */
export function deleteFile(id: number) {
  return http.delete<Result<null>>(`/files/${id}`)
}

/** 失败文件重试：仅 FAILED 可重试，事务外重新触发异步解析管道 */
export function retryFile(id: number, modelVersion?: string) {
  return http.post<Result<null>>(`/files/${id}/retry`, null, modelVersion ? {
    headers: { 'X-Coffer-Model-Version': modelVersion, 'X-Coffer-Allow-Sensitive': 'true' },
  } : undefined)
}

export type ModelCapability = 'CHAT' | 'VISION' | 'EMBEDDING'
export interface FileModelAssessment {
  fileId: number
  revision: number
  contentSha256: string
  risk: 'CLEAR' | 'SENSITIVE' | 'UNKNOWN'
  configurationVersion: string
  mode: 'API' | 'LOCAL'
  capability: ModelCapability
  endpointUrl: string
  modelName: string
}

export function assessFileModel(id: number, capability: ModelCapability) {
  return http.get<Result<FileModelAssessment>>(`/files/${id}/model-approval`, { params: { capability } })
}

export function approveFileModel(id: number, capability: ModelCapability, assessment: FileModelAssessment) {
  return http.post<Result<FileModelAssessment>>(`/files/${id}/model-approval`, {
    approved: true, revision: assessment.revision, contentSha256: assessment.contentSha256,
    risk: assessment.risk,
  }, {
    params: { capability },
    headers: { 'X-Coffer-Model-Version': assessment.configurationVersion, 'X-Coffer-Allow-Sensitive': 'true' },
  })
}

export interface WorkSaveResponse {
  operationId: string
  fileId: number
  revision: number
  taskId: string
}

/** Saves edited bytes only if the formal revision and SHA-256 still match. Reuse requestId on retry. */
export function saveWorkingCopy(id: number, file: File, expectedRevision: number,
                                expectedSha256: string, requestId: string) {
  const body = new FormData()
  body.append('file', file)
  body.append('expectedRevision', String(expectedRevision))
  body.append('expectedSha256', expectedSha256)
  return http.post<Result<WorkSaveResponse>>(`/files/${id}/work-save`, body, {
    headers: { 'Idempotency-Key': requestId },
  })
}

/** 文件上传：multipart/form-data，返回 taskId 供轮询 */
export function uploadFile(
  formData: FormData,
  idempotencyKey: string,
  onUploadProgress?: (percent: number) => void,
) {
  return http.post<Result<FileUploadResponse>>('/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data', 'Idempotency-Key': idempotencyKey },
    onUploadProgress: (e) => {
      if (e.total) onUploadProgress?.(Math.round((e.loaded / e.total) * 100))
    },
  })
}
