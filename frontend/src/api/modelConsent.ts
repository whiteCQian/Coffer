import { ElMessageBox } from 'element-plus'
import http from './http'
import type { Result } from './types'
import { useAuthStore } from '@/stores/auth'

export interface ModelTarget {
  configurationVersion: string
  mode: 'API' | 'LOCAL'
  targets: { capability: string; baseUrl: string; modelName: string }[]
}

export async function confirmModelTarget(inbox = false) {
  const owner = useAuthStore().user?.id
  const { data } = await http.get<Result<ModelTarget>>('/model-execution/target')
  const target = data.data
  const destinations = target.targets.map(t => `${t.capability}：${t.baseUrl}（${t.modelName}）`).join('\n')
  await ElMessageBox.confirm(
    `本次任务使用${target.mode === 'LOCAL' ? '本地' : 'API'}模型：\n${destinations}\n\n` +
    '所选文件、提取的正文、对话历史和检索引用可能发送到以上端点；任务及其后台补建将固定使用本次配置。命中敏感规则或无法在本地判定的文件，在发送正文前还需逐文件授权。' +
    (inbox ? '\n\n自动导入会持续使用此模型配置；后续敏感或未知文件仍需逐文件授权。你可随时在设置中撤销自动导入授权。' : ''),
    '确认 AI 内容发送目标',
    { confirmButtonText: '同意并提交', cancelButtonText: '取消', type: 'warning', customClass: 'model-consent-dialog' },
  )
  if (!owner || owner !== useAuthStore().user?.id) throw new Error('账号已变化，请重新提交')
  return target.configurationVersion
}

export function needsModelConsent(method: string | undefined, url: string | undefined) {
  if (method?.toLowerCase() !== 'post') return false
  return /^\/(model-execution\/inbox|inbox-imports\/\d+\/confirm|desktop\/work-copies\/[0-9a-f-]+\/save|chat\/send|files\/upload|files\/tags\/suggest|files\/\d+\/(?:retry|model-approval|work-save)|vector\/reindex|governance\/previews(?:\/reanalyze(?:\/\d+)?|\/[^/]+\/regenerate)?)$/.test(url ?? '')
}
