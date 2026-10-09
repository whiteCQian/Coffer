import http from './http'
import type { ApiSchema, Result } from './types'

export type WorkCopyView = {
  [K in keyof Required<ApiSchema['WorkCopyView']>]: K extends 'errorCode' | 'sha256' | 'size' | 'modifiedTime' | 'recoveredFileId'
    ? Required<ApiSchema['WorkCopyView']>[K] | null : Required<ApiSchema['WorkCopyView']>[K]
}
export const capabilities = () => http.get<Result<{ workCopies: boolean }>>('/desktop/capabilities', { quietError: true })
export const list = (page = 0) => http.get<Result<WorkCopyView[]>>('/desktop/work-copies', { params: { page } })
export const create = (fileId: number) => http.post<Result<WorkCopyView>>('/desktop/work-copies', { fileId })
export const open = (id: string) => http.post<Result<WorkCopyView>>(`/desktop/work-copies/${encodeURIComponent(id)}/open`)
export const save = (id: string) => http.post<Result<WorkCopyView>>(`/desktop/work-copies/${encodeURIComponent(id)}/save`)
export const saveAs = (id: string) => http.post<Result<WorkCopyView>>(`/desktop/work-copies/${encodeURIComponent(id)}/save-as`)
export const close = (id: string, action: string, sha256?: string | null) =>
  http.post<Result<WorkCopyView>>(`/desktop/work-copies/${encodeURIComponent(id)}/close`, { action, sha256 })
