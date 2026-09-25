import http from './http'
import type { ConfirmTagRequest, RejectTagRequest, Result, TagCandidate } from './types'

/** 确认文件标签关联 */
export function confirmTag(payload: ConfirmTagRequest) {
  return http.post<Result<null>>('/files/tags/confirm', payload)
}

/** 拒绝文件标签关联，可附带修正标签名 */
export function rejectTag(payload: RejectTagRequest) {
  return http.post<Result<null>>('/files/tags/reject', payload)
}

/** 标签候选池：库中被文件确认采用过的标签及覆盖文件数（按覆盖数倒序） */
export function listTagCandidates() {
  return http.get<Result<TagCandidate[]>>('/files/tags/candidates')
}

/** 智能标签联想：把自然语言描述映射为库中真实存在的候选标签（可为空） */
export function suggestTags(q: string) {
  return http.get<Result<TagCandidate[]>>('/files/tags/suggest', { params: { q } })
}
