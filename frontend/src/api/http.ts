import { ElMessage } from 'element-plus'
import axios from 'axios'
import { currentBoundary } from './sessionBoundary'

declare module 'axios' {
  interface InternalAxiosRequestConfig { identityEpoch?: number }
}

import type { Result } from './types'

/**
 * axios 实例：统一 baseURL（dev 经 Vite 代理 /api → 后端 8080）与
 * Result<T> 拦截。业务错误（HTTP 恒 200、body.code!==0）在此统一弹错并 reject，
 * 组件内仅处理成功分支与网络/框架级异常。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
  withCredentials: true,
  withXSRFToken: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

http.interceptors.request.use(async (config) => {
  const boundary = currentBoundary()
  config.identityEpoch = boundary.epoch
  config.signal = boundary.signal
  const { needsModelConsent, confirmModelTarget } = await import('./modelConsent')
  if (needsModelConsent(config.method, config.url)) {
    config.headers.set('X-Coffer-Model-Version', await confirmModelTarget(config.url === '/model-execution/inbox'))
    config.headers.set('X-Coffer-Allow-Sensitive', 'true')
  }
  if (boundary.epoch !== currentBoundary().epoch) throw new axios.CanceledError('账号已变化')
  return config
})

http.interceptors.response.use(
  (response) => {
    if (response.config.identityEpoch !== currentBoundary().epoch) return Promise.reject(new axios.CanceledError('账号已变化'))
    const body = response.data as Result<unknown>
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return response
  },
  (error) => {
    if (axios.isCancel(error) || error === 'cancel' || error === 'close') return Promise.reject(error)
    if (error.config?.identityEpoch !== undefined && error.config.identityEpoch !== currentBoundary().epoch) return Promise.reject(new axios.CanceledError('账号已变化'))
    const status = error.response?.status
    const url = String(error.config?.url || '')
    if (status === 401 && url.endsWith('/auth/me')) {
      return Promise.reject(error)
    }
    if (status === 401 && !url.startsWith('/auth/')) {
      window.dispatchEvent(new CustomEvent('coffer:auth-expired'))
      return Promise.reject(error)
    }
    const msg =
      error.response?.data?.msg || error.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default http
