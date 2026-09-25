import http from './http'
import type { ChatQueryResponse, ChatRequest, Result } from './types'

/** 对话搜索：sessionId 为空则由服务端生成，前端须回传以保持会话记忆 */
export function sendMessage(payload: ChatRequest) {
  return http.post<Result<ChatQueryResponse>>('/chat/send', payload)
}
