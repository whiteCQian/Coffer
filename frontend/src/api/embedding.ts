import http from './http'
import type { Result } from './types'
import type { RuntimeMode } from './modelRuntime'

export interface AdminEmbeddingConfig {
  mode: RuntimeMode; baseUrl: string; modelName: string; dimensions: number
  configured: boolean; maskedApiKey: string; enabled: boolean; validatedAt: string | null; configurationVersion: string
}
export interface EmbeddingStatus {
  configured: boolean; enabled: boolean; available: boolean; mode: RuntimeMode
  baseUrl: string; modelName: string; dimensions: number; message: string
}
export function getEmbeddingStatus(mode: RuntimeMode) {
  return http.get<Result<EmbeddingStatus>>('/settings/embedding', { params: { mode } })
}
export function getAdminEmbedding() { return http.get<Result<AdminEmbeddingConfig>>('/admin/embedding') }
export function saveAdminEmbedding(config: { mode: RuntimeMode; baseUrl: string; modelName: string; dimensions: number; apiKey?: string }) {
  return http.put<Result<AdminEmbeddingConfig>>('/admin/embedding', config)
}
export function testAdminEmbedding() {
  return http.post<Result<{ success: boolean; message: string; configuration: AdminEmbeddingConfig }>>('/admin/embedding/test', {}, { timeout: 90000 })
}
export function enableAdminEmbedding(enabled: boolean) { return http.put<Result<AdminEmbeddingConfig>>('/admin/embedding/enabled', { enabled }) }
export function clearAdminEmbedding() { return http.delete<Result<AdminEmbeddingConfig>>('/admin/embedding') }
