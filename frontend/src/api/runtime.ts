import http from './http'
import type { ApiSchema, Result } from './types'
export type RuntimeComponent = Omit<Required<ApiSchema['Component']>, 'totalBytes' | 'freeBytes'> & { totalBytes: number | null; freeBytes: number | null }
export type RuntimeSnapshot = Omit<Required<ApiSchema['Snapshot']>, 'components' | 'alerts'> & { components: RuntimeComponent[]; alerts: Required<ApiSchema['Alert']>[] }
export type KeyRotation = Required<ApiSchema['Status']>
export type AlertEvent = Omit<Required<ApiSchema['AlertEvent']>, 'resolvedAt'> & { resolvedAt: string | null }
export const runtimeStatus = (admin = false) => http.get<Result<RuntimeSnapshot>>(admin ? '/admin/runtime' : '/runtime/status', { quietError: true, timeout: 8000 })
export const rotationStatus = () => http.get<Result<KeyRotation>>('/admin/runtime/key-rotation', { quietError: true })
export const rotateMasterKey = () => http.post<Result<KeyRotation>>('/admin/runtime/key-rotation', undefined, { timeout: 120000 })
export const alertHistory = () => http.get<Result<AlertEvent[]>>('/admin/runtime/alerts', { quietError: true })
export const runtimeLabels: Record<string, string> = { database: '数据库', redis: 'Redis', storage: '文件存储', capacity: '存储容量', backup: '备份', UP: '正常', DOWN: '故障', UNKNOWN: '未核实', NOT_REQUIRED: '无需依赖', OUT_OF_SERVICE: '状态采样不可用', OK: '检查通过', PROBE_TIMEOUT: '检查超时或不可用', DATABASE_UNAVAILABLE: '数据库不可用', REDIS_UNAVAILABLE: 'Redis 不可用', STORAGE_UNAVAILABLE: '文件存储不可用', BUCKET_VERSIONING_DISABLED: '存储桶未启用版本控制', VOLUME_NOT_CONFIGURED: '容量监控未配置', RECEIPT_NOT_CONFIGURED: '备份回执未配置', RECEIPT_INVALID: '备份回执无效', BACKUP_STALE: '最近备份已过期', MONITOR_STALE: '采样已过期', DISK_LOW: '存储空间不足', OPTIONAL_DESKTOP: '桌面可选依赖' }
export const runtimeActions: Record<string, string> = { NONE: '无需操作', CHECK_STORAGE: '检查存储服务、挂载和访问权限，恢复后重试', CHECK_DATABASE: '检查数据库服务、连接和容量，恢复后重试', CHECK_REDIS: '检查 Redis 服务和连接，恢复后重试', CHECK_BUCKET_VERSIONING: '核对存储桶版本控制配置后重新检查', CHECK_CAPACITY: '检查容量采样配置和存储卷访问权限', CONFIGURE_CAPACITY: '配置存储服务所在卷的容量监控路径', FREE_CAPACITY: '扩容或按保留策略清理空间后重试', CONFIGURE_BACKUP: '配置备份作业的已校验完成回执', CHECK_BACKUP: '检查备份作业与完成回执', RUN_VERIFIED_BACKUP: '完成备份与校验后更新回执', CHECK_WORKERS: '检查后台工作进程和积压，恢复后等待重新采样', OWNER_REVIEW: '请相关用户在操作台核对人工处理项', OWNER_RETRY: '请相关用户查看失败任务并按提示重试' }
