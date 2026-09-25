import http from './http'
import type {
  Category,
  CategoryCountResponse,
  ChangeCategoryRequest,
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
export function getFileDetail(id: number) {
  return http.get<Result<FileDetailResponse>>(`/files/${id}`)
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

/** 文件改分类（归档文件自动触发物理搬移到新分类目录） */
export function changeCategory(id: number, payload: ChangeCategoryRequest) {
  return http.put<Result<FileDetailResponse>>(`/files/${id}/category`, payload)
}

/** 删除文件：允许任意状态，级联清理标签/任务/MinIO 对象 */
export function deleteFile(id: number) {
  return http.delete<Result<null>>(`/files/${id}`)
}

/** 失败文件重试：仅 FAILED 可重试，事务外重新触发异步解析管道 */
export function retryFile(id: number) {
  return http.post<Result<null>>(`/files/${id}/retry`)
}

/** 文件上传：multipart/form-data，返回 taskId 供轮询 */
export function uploadFile(
  formData: FormData,
  onUploadProgress?: (percent: number) => void,
) {
  return http.post<Result<FileUploadResponse>>('/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => {
      if (e.total) onUploadProgress?.(Math.round((e.loaded / e.total) * 100))
    },
  })
}
