import http from './http'
import type { Result } from './types'

export type RuntimeMode = 'API' | 'LOCAL'
export type RuntimeCapability = 'CHAT' | 'VISION' | 'EMBEDDING'

export interface RuntimeModeStatus {
  activeMode: RuntimeMode
  currentModeValidated: boolean
  validatedModes: Record<RuntimeMode, boolean>
  validatedAt: Record<RuntimeMode, string | null>
  validationSuccess: boolean | null
  message: string | null
  capabilities: Record<string, string>
}

export interface RuntimeEndpointConfig {
  mode: RuntimeMode
  capability: RuntimeCapability
  baseUrl: string
  modelName: string
  configured: boolean
  maskedApiKey: string
  credentialProvider: 'DEEPSEEK' | 'QWEN_VL' | null
  source: 'DEFAULT' | 'CUSTOM' | 'REQUEST'
  embeddingEnabled: boolean
}

export interface RuntimeEndpointRequest {
  mode: RuntimeMode
  capability: RuntimeCapability
  baseUrl?: string
  modelName?: string
  apiKey?: string
}

export interface RuntimeEndpointTestResponse {
  success: boolean
  capability: RuntimeCapability
  message: string
}

export function getRuntimeModeStatus() {
  return http.get<Result<RuntimeModeStatus>>('/settings/runtime')
}

export function validateRuntimeMode(mode: RuntimeMode) {
  return http.post<Result<RuntimeModeStatus>>('/settings/runtime/validate', { mode })
}

export function switchRuntimeMode(mode: RuntimeMode) {
  return http.put<Result<RuntimeModeStatus>>('/settings/runtime/mode', { mode })
}

export function getRuntimeEndpointConfigs() {
  return http.get<Result<RuntimeEndpointConfig[]>>('/settings/runtime/config')
}

export function saveRuntimeEndpoint(config: RuntimeEndpointRequest) {
  return http.put<Result<RuntimeEndpointConfig>>(
    `/settings/runtime/config/${config.mode}/${config.capability}`,
    config,
  )
}

export function deleteRuntimeEndpoint(mode: RuntimeMode, capability: RuntimeCapability) {
  return http.delete<Result<null>>(`/settings/runtime/config/${mode}/${capability}`)
}

export function testRuntimeEndpoint(request: RuntimeEndpointRequest) {
  return http.post<Result<RuntimeEndpointTestResponse>>('/settings/runtime/config/test', request)
}
