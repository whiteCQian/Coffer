import { ElMessage } from 'element-plus'
import axios from 'axios'

import type { Result } from './types'

/**
 * axios 实例：统一 baseURL（dev 经 Vite 代理 /api → 后端 8080）与
 * Result<T> 拦截。业务错误（HTTP 恒 200、body.code!==0）在此统一弹错并 reject，
 * 组件内仅处理成功分支与网络/框架级异常。
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

http.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.msg || '请求失败')
      return Promise.reject(new Error(body.msg || '请求失败'))
    }
    return response
  },
  (error) => {
    const msg =
      error.response?.data?.msg || error.message || '网络异常，请稍后重试'
    ElMessage.error(msg)
    return Promise.reject(error)
  },
)

export default http
