/** Chat permission is scoped to an account, purpose and immutable model configuration. */
export interface ChatTarget {
  configurationVersion: string
  mode: 'API' | 'LOCAL'
  targets: { capability: string; baseUrl: string; modelName: string }[]
  chatConsentApproved?: boolean
}
type Identity = { owner?: number; epoch?: number }
type Grant = { scope: string; version: string }
const SCOPE = 'account-file-chat-v1'
const PREFIX = 'coffer:chat-target-consent:'

export function createChatTargetConsent(options: {
  identity: () => Identity
  target: () => Promise<ChatTarget>
  confirm: (message: string) => Promise<unknown>
  storage: Pick<Storage, 'getItem' | 'setItem'>
}) {
  const remembered = new Map<number, Grant>()
  const pending = new Map<string, Promise<void>>()
  function requireIdentity(original: Identity) {
    const current = options.identity()
    if (!original.owner || current.owner !== original.owner || current.epoch !== original.epoch)
      throw new Error('账号已变化，请重新提交')
  }
  function read(owner: number): Grant | undefined {
    try {
      const grant = JSON.parse(options.storage.getItem(PREFIX + owner) || 'null')
      if (grant?.scope === SCOPE && typeof grant.version === 'string') return grant
    } catch { /* Keep the in-memory grant when browser storage is unavailable. */ }
    return remembered.get(owner)
  }
  return async function currentChatTargetVersion() {
    const identity = options.identity()
    const target = await options.target()
    requireIdentity(identity)
    if (target.chatConsentApproved === true) return target.configurationVersion
    const owner = identity.owner!
    const grant = read(owner)
    if (grant?.version === target.configurationVersion) return target.configurationVersion
    const key = `${owner}:${identity.epoch}:${target.configurationVersion}`
    let confirmation = pending.get(key)
    if (!confirmation) {
      confirmation = (async () => {
        const destinations = target.targets.map(t => `${t.capability}：${t.baseUrl}（${t.modelName}）`).join('\n')
        await options.confirm(
          (grant ? '模型配置或授权范围已变化，需要重新确认。\n\n' : '') +
          `AI 对话将使用${target.mode === 'LOCAL' ? '本地' : 'API'}模型：\n${destinations}\n\n` +
          '用于回答问题、查找文件；可能发送你的提问、对话历史及当前账号文件的检索引用或正文。' +
          '\n同一账号、同一配置下的后续对话不再重复询问；这不授权自动导入或其他整理任务。' +
          '文件版本或内容有单独授权要求时，仍会提示。')
        requireIdentity(identity)
        const approved = { scope: SCOPE, version: target.configurationVersion }
        remembered.set(owner, approved)
        try { options.storage.setItem(PREFIX + owner, JSON.stringify(approved)) } catch { /* Memory still prevents repeated prompts. */ }
      })()
      pending.set(key, confirmation)
    }
    try { await confirmation; requireIdentity(identity); return target.configurationVersion }
    finally { if (pending.get(key) === confirmation) pending.delete(key) }
  }
}
