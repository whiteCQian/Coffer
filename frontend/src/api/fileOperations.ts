import http from './http'
import type { Result } from './types'

export interface WriteIntentView {
  id: string
  taskId: string
  fileName: string
  kind: string
  status: string
  errorCode: string | null
  attempts: number
  nextAttemptAt: string | null
  createdAt: string
  retentionUntil: string | null
  discardedAt: string | null
}

export interface DeletionView {
  id: number
  fileId: number
  status: string
  attempts: number
  errorCode: string | null
  createdAt: string
  retentionUntil: string | null
  deletedAt: string | null
}

export interface RenameView {
  id: string
  fileId: number
  beforeName: string
  afterName: string
  status: string
  errorCode: string | null
  attempts: number
  nextAttemptAt: string | null
  createdAt: string
}

export interface WorkSaveView {
  id: string
  fileId: number
  fileName: string
  status: string
  errorCode: string | null
  attempts: number
  expectedRevision: number
  recoveredFileId: number | null
  createdAt: string
}

export const listWriteIntents = (page = 0) => http.get<Result<WriteIntentView[]>>('/file-operations/writes', { params: { page } })
export const restoreWrite = (id: string) => http.post<Result<number>>(`/file-operations/writes/${encodeURIComponent(id)}/restore`)
export const discardWrite = (id: string, sha256: string) =>
  http.post<Result<void>>(`/file-operations/writes/${encodeURIComponent(id)}/discard`, { sha256 })
export const reconcileWrite = (id: string) => http.post<Result<void>>(`/file-operations/writes/${encodeURIComponent(id)}/reconcile`)
export const listDeletionTasks = (page = 0) => http.get<Result<DeletionView[]>>('/file-operations/deletions', { params: { page } })
export const retryDeletion = (id: number) => http.post<Result<void>>(`/file-operations/deletions/${id}/retry`)
export const confirmDeletion = (id: number, sha256: string) =>
  http.post<Result<void>>(`/file-operations/deletions/${id}/confirm`, { sha256 })
export const listRenameIntents = (page = 0) => http.get<Result<RenameView[]>>('/file-operations/renames', { params: { page } })
export const retryRename = (id: string) => http.post<Result<void>>(`/file-operations/renames/${encodeURIComponent(id)}/retry`)
export const listWorkSaves = (page = 0) => http.get<Result<WorkSaveView[]>>('/file-operations/work-saves', { params: { page } })
export const reconcileWorkSave = (id: string) => http.post<Result<void>>(`/file-operations/work-saves/${encodeURIComponent(id)}/reconcile`)
export const restoreWorkSave = (id: string) => http.post<Result<number>>(`/file-operations/work-saves/${encodeURIComponent(id)}/restore`)
