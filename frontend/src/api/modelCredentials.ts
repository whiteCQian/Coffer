import http from './http'
import type { ModelCredentialStatusResponse, ModelCredentialTestResponse, ModelProvider, Result } from './types'

export function getModelCredentialStatus() {
  return http.get<Result<ModelCredentialStatusResponse>>('/settings/models')
}

export function saveModelCredential(provider: ModelProvider, apiKey: string) {
  return http.put<Result<null>>(`/settings/models/${provider}`, { apiKey })
}

export function deleteModelCredential(provider: ModelProvider) {
  return http.delete<Result<null>>(`/settings/models/${provider}`)
}

export function testModelCredential(provider: ModelProvider, apiKey: string) {
  return http.post<Result<ModelCredentialTestResponse>>(`/settings/models/${provider}/test`, { apiKey })
}
