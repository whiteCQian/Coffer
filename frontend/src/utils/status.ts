import type { ConfirmationStatus, FileStatus, TagStatus } from '@/api/types'

/** 文件处理状态 → 展示文案 + 语义色 */
export const FILE_STATE_META: Record<FileStatus, { label: string; tone: 'ok' | 'run' | 'warn' | 'fail' }> = {
  COMPLETED: { label: '待确认', tone: 'ok' },
  PROCESSING: { label: 'AI 处理中', tone: 'run' },
  FAILED: { label: '已保存，需处理', tone: 'fail' },
  PENDING: { label: '排队中', tone: 'warn' },
}

/** 状态 → 用于 <span> 的 class 片段 */
export function fileStateText(status: FileStatus): string {
  return FILE_STATE_META[status].label
}

/** 标签汇总态简表（供“全部文件”卡片角标快速区分） */
export const TAG_STATUS_MINI: Record<TagStatus, string> = {
  ALL_CONFIRMED: '已确认',
  PENDING: '待确认',
  ALL_REJECTED: '已拒绝',
  NO_TAG: '无标签',
}

/** 单标签确认态是否仍待用户动作 */
export function isPendingTag(status: ConfirmationStatus): boolean {
  return status === 'PENDING_CONFIRMATION'
}

export function taskStateLabel(file: { taskState?: string | null; archived?: boolean; status?: string | null }) {
  const labels: Record<string,string> = { ARCHIVING:'正在归档', ROLLING_BACK:'正在撤销整理', ARCHIVE_FAILED:'已保存，归档需处理', ARCHIVE_BLOCKED:'已保存，整理需核对', ARCHIVED:'已归档', SAVED_NEEDS_ATTENTION:'已保存，稍后整理', AWAITING_CONFIRMATION:'待确认', SAVED_ANALYZING:'已保存，正在整理' }
  return labels[file.taskState ?? ''] ?? (file.archived ? '已归档' : file.status === 'COMPLETED' ? '待确认' : file.status === 'FAILED' ? '已保存，稍后整理' : '已保存，正在整理')
}
