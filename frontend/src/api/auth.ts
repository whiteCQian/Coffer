import http from './http'
import type { Result } from './types'

export interface AuthUser {
  id: number
  username: string
  role: 'ADMIN' | 'USER'
  enabled: boolean
  createdAt: string
}

export interface AuthStatus {
  setupRequired: boolean
  setupAvailable: boolean
}

export interface AccountAudit {
  id: number
  actorUserId: number
  actorUsername: string
  targetUserId: number | null
  action: string
  createdAt: string
}

export const getCsrfToken = () => http.get<Result<string>>('/auth/csrf')
export const getAuthStatus = () => http.get<Result<AuthStatus>>('/auth/status')
export const getCurrentUser = () => http.get<Result<AuthUser>>('/auth/me')
export const login = (username: string, password: string) =>
  http.post<Result<AuthUser>>('/auth/login', { username, password })
export const setupInitialAdmin = (setupToken: string, username: string, password: string) =>
  http.post<Result<AuthUser>>('/auth/setup', { setupToken, username, password })
export const logout = () => http.post('/auth/logout')
export const changePassword = (currentPassword: string, newPassword: string) =>
  http.post('/auth/password', { currentPassword, newPassword })
export const listUsers = () => http.get<Result<AuthUser[]>>('/admin/users')
export const createUser = (username: string, password: string) =>
  http.post<Result<AuthUser>>('/admin/users', { username, password })
export const disableUser = (id: number) => http.patch(`/admin/users/${id}/disable`)
export const enableUser = (id: number) => http.patch(`/admin/users/${id}/enable`)
export const resetUserPassword = (id: number, newPassword: string) =>
  http.post(`/admin/users/${id}/reset-password`, { newPassword })
export const getAccountAudit = () => http.get<Result<AccountAudit[]>>('/admin/audit')
