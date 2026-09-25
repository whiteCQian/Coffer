import http from './http'
import type {
  ArchiveOperationBatchResponse,
  ArchiveOperationBatchSummaryResponse,
  ArchiveOperationItemResponse,
  ConfirmGovernancePreviewRequest,
  CreateGovernancePreviewRequest,
  GovernancePreviewResponse,
  GovernanceCompensationTaskResponse,
  RegenerateGovernancePreviewRequest,
  ReanalyzeGovernanceFileRequest,
  ReanalyzeGovernanceFilesRequest,
  Result,
  SkipGovernancePreviewItemRequest,
  UpdateGovernancePreviewItemRequest,
} from './types'

export interface ArchiveOperationBatchQuery {
  batchId?: string
  fileId?: number
  status?: string
  page?: number
  size?: number
}

export interface ArchiveOperationItemQuery {
  batchId?: string
  fileId?: number
  status?: string
  page?: number
  size?: number
}

/** Create a persisted dry-run analysis preview for selected existing files. */
export function createGovernancePreview(payload: CreateGovernancePreviewRequest) {
  return http.post<Result<GovernancePreviewResponse>>('/governance/previews', payload)
}

/** Re-analyze one or more existing files into a new, non-destructive preview. */
export function reanalyzeGovernanceFiles(payload: ReanalyzeGovernanceFilesRequest) {
  return http.post<Result<GovernancePreviewResponse>>('/governance/previews/reanalyze', payload)
}

/** Re-analyze one existing file into a new, non-destructive preview. */
export function reanalyzeGovernanceFile(
  fileId: number,
  payload: ReanalyzeGovernanceFileRequest,
) {
  return http.post<Result<GovernancePreviewResponse>>(
    `/governance/previews/reanalyze/${fileId}`,
    payload,
  )
}

/** Read a preview batch and lazily apply expiry rules. */
export function getGovernancePreview(previewId: string) {
  return http.get<Result<GovernancePreviewResponse>>(`/governance/previews/${previewId}`)
}

/** Re-run analysis for the same file set with a new idempotency key. */
export function regenerateGovernancePreview(
  previewId: string,
  payload: RegenerateGovernancePreviewRequest,
) {
  return http.post<Result<GovernancePreviewResponse>>(
    `/governance/previews/${previewId}/regenerate`,
    payload,
  )
}

/** Save edited suggestion values for one preview item. */
export function updateGovernancePreviewItem(
  previewId: string,
  itemId: number,
  payload: UpdateGovernancePreviewItemRequest,
) {
  return http.patch<Result<GovernancePreviewResponse>>(
    `/governance/previews/${previewId}/items/${itemId}`,
    payload,
  )
}

/** Skip one preview item with an optional user reason. */
export function skipGovernancePreviewItem(
  previewId: string,
  itemId: number,
  payload: SkipGovernancePreviewItemRequest = {},
) {
  return http.post<Result<GovernancePreviewResponse>>(
    `/governance/previews/${previewId}/items/${itemId}/skip`,
    payload,
  )
}

/** Confirm all or a selected subset of preview items for C07 execution. */
export function confirmGovernancePreview(
  previewId: string,
  payload: ConfirmGovernancePreviewRequest,
) {
  return http.post<Result<GovernancePreviewResponse>>(
    `/governance/previews/${previewId}/confirm`,
    payload,
  )
}

/** Cancel a preview batch before confirmation. */
export function cancelGovernancePreview(previewId: string) {
  return http.post<Result<GovernancePreviewResponse>>(
    `/governance/previews/${previewId}/cancel`,
  )
}

/** Read the latest C07 archive execution batch for a preview. */
export function getArchiveOperation(batchId: string) {
  return http.get<Result<ArchiveOperationBatchResponse>>(`/governance/operations/${batchId}`)
}

/** Retry failed items in a C07 archive execution batch. */
export function retryArchiveOperation(batchId: string) {
  return http.post<Result<ArchiveOperationBatchResponse>>(
    `/governance/operations/${batchId}/retry`,
  )
}

/** Restore every reversible file in an archive batch. */
export function rollbackArchiveOperation(batchId: string) {
  return http.post<Result<ArchiveOperationBatchResponse>>(`/governance/operations/${batchId}/rollback`)
}

/** Restore one archived file to its recorded original location. */
export function rollbackArchiveOperationItem(batchId: string, itemId: number) {
  return http.post<Result<ArchiveOperationBatchResponse>>(
    `/governance/operations/${batchId}/items/${itemId}/rollback`,
  )
}

export function getGovernanceCompensations(batchId: string) {
  return http.get<Result<GovernanceCompensationTaskResponse[]>>(
    `/governance/operations/${batchId}/compensations`,
  )
}

export function retryGovernanceCompensations(batchId: string) {
  return http.post<Result<GovernanceCompensationTaskResponse[]>>(
    `/governance/operations/${batchId}/compensations/retry`,
  )
}

/** Search archive operation batches for the C08 ledger page. */
export function searchArchiveOperationBatches(params: ArchiveOperationBatchQuery = {}) {
  return http.get<Result<{
    content: ArchiveOperationBatchSummaryResponse[]
    totalElements: number
    totalPages: number
    number: number
    size: number
  }>>('/governance/operations', { params })
}

/** Search file-level archive operation ledger rows. */
export function searchArchiveOperationItems(params: ArchiveOperationItemQuery = {}) {
  return http.get<Result<{
    content: ArchiveOperationItemResponse[]
    totalElements: number
    totalPages: number
    number: number
    size: number
  }>>('/governance/operations/items', { params })
}

/** Export filtered file-level ledger rows as JSON or CSV. */
export function exportArchiveOperations(
  params: Omit<ArchiveOperationItemQuery, 'page' | 'size'> & { format: 'json' | 'csv' },
) {
  return http.get<Blob>('/governance/operations/export', {
    params,
    responseType: 'blob',
  })
}
