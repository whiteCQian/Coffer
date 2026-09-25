import http from './http'
import type {
  InboxImportProgressResponse,
  Result,
  TaskOverviewResponse,
  TaskProgressResponse,
} from './types'

/** 异步任务进度查询（上传后轮询：PENDING → PROCESSING → COMPLETED/FAILED） */
export function getTaskProgress(taskId: string) {
  return http.get<Result<TaskProgressResponse>>(`/tasks/${taskId}`)
}

/** 首页总览：处理中/失败/待确认标签三类面板的计数与明细 */
export function getOverview() {
  return http.get<Result<TaskOverviewResponse>>('/tasks/overview')
}

/** 收件箱批量导入进度（C04） */
export function getInboxImportProgress() {
  return http.get<Result<InboxImportProgressResponse>>('/inbox-imports/progress')
}
