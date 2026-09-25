<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  deleteRuntimeEndpoint,
  getRuntimeEndpointConfigs,
  getRuntimeModeStatus,
  saveRuntimeEndpoint,
  switchRuntimeMode,
  testRuntimeEndpoint,
  validateRuntimeMode,
  type RuntimeCapability,
  type RuntimeEndpointConfig,
  type RuntimeEndpointRequest,
  type RuntimeMode,
  type RuntimeModeStatus,
} from '@/api/modelRuntime'

type EndpointCard = RuntimeEndpointConfig & {
  apiKey: string
  saving: boolean
  testing: boolean
}

const modes: RuntimeMode[] = ['API', 'LOCAL']
const capabilities: RuntimeCapability[] = ['CHAT', 'VISION', 'EMBEDDING']
const capabilityMeta: Record<RuntimeCapability, { title: string; description: string; icon: string }> = {
  CHAT: { title: 'Chat', description: '对话、摘要和标签生成', icon: '对' },
  VISION: { title: 'Vision', description: '图片与视觉内容理解', icon: '视' },
  EMBEDDING: { title: 'Embedding', description: '向量检索与语义匹配', icon: '向' },
}

const loading = ref(true)
const modeLoading = ref(false)
const selectedMode = ref<RuntimeMode>('API')
const modeStatus = ref<RuntimeModeStatus | null>(null)
const cards = reactive<EndpointCard[]>([])

const activeCards = computed(() => cards.filter((card) => card.mode === selectedMode.value))
const selectedModeValidated = computed(() => Boolean(modeStatus.value?.validatedModes?.[selectedMode.value]))
const activeMode = computed(() => modeStatus.value?.activeMode ?? 'API')
const chatCard = computed(() => activeCards.value.find((card) => card.capability === 'CHAT'))
const embeddingCard = computed(() => activeCards.value.find((card) => card.capability === 'EMBEDDING'))
const visionCard = computed(() => activeCards.value.find((card) => card.capability === 'VISION'))
const chatRagSaving = ref(false)
const ragTesting = ref(false)
const shareChatRagEndpoint = ref(false)
const showRagAdvanced = ref(false)

function cardFor(capability: RuntimeCapability) {
  return activeCards.value.find((card) => card.capability === capability)
}

function syncChatRagSharing() {
  const chat = chatCard.value
  const embedding = embeddingCard.value
  shareChatRagEndpoint.value = Boolean(chat && embedding && chat.baseUrl === embedding.baseUrl)
}

function modeLabel(mode: RuntimeMode) {
  return mode === 'API' ? 'API 模式' : '本地模式'
}

function capabilityLabel(capability: RuntimeCapability) {
  return capabilityMeta[capability].title
}

function formatDate(value: string | null | undefined) {
  if (!value) return '尚未验证'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function applyConfigs(configs: RuntimeEndpointConfig[]) {
  cards.splice(0, cards.length, ...configs.map((config) => ({
    ...config,
    apiKey: '',
    saving: false,
    testing: false,
  })))
  syncChatRagSharing()
}

async function load() {
  loading.value = true
  try {
    const [statusResponse, configResponse] = await Promise.all([
      getRuntimeModeStatus(),
      getRuntimeEndpointConfigs(),
    ])
    modeStatus.value = statusResponse.data.data
    selectedMode.value = statusResponse.data.data.activeMode
    applyConfigs(configResponse.data.data)
  } finally {
    loading.value = false
  }
}

async function validateSelectedMode() {
  modeLoading.value = true
  try {
    const { data } = await validateRuntimeMode(selectedMode.value)
    modeStatus.value = data.data
    if (data.data.validationSuccess) ElMessage.success(`${modeLabel(selectedMode.value)}校验通过`)
    else ElMessage.warning(data.data.message || '部分模型能力校验失败')
  } finally {
    modeLoading.value = false
  }
}

async function enableSelectedMode() {
  if (!selectedModeValidated.value) {
    ElMessage.warning('请先完成当前模式的连通性校验')
    return
  }
  modeLoading.value = true
  try {
    const { data } = await switchRuntimeMode(selectedMode.value)
    modeStatus.value = data.data
    ElMessage.success(`已启用${modeLabel(selectedMode.value)}`)
  } finally {
    modeLoading.value = false
  }
}

async function testCard(card: EndpointCard) {
  card.testing = true
  try {
    const { data } = await testRuntimeEndpoint({
      mode: card.mode,
      capability: card.capability,
      baseUrl: card.baseUrl,
      modelName: card.modelName,
      apiKey: card.apiKey.trim() || undefined,
    })
    if (data.data.success) ElMessage.success(`${capabilityLabel(card.capability)} 连接成功`)
    else ElMessage.error(data.data.message)
  } finally {
    card.testing = false
  }
}

async function testRag() {
  const chat = chatCard.value
  const embedding = embeddingCard.value
  if (!chat || !embedding) return
  ragTesting.value = true
  try {
    const { data } = await testRuntimeEndpoint({
      mode: embedding.mode,
      capability: 'EMBEDDING',
      baseUrl: shareChatRagEndpoint.value ? chat.baseUrl : embedding.baseUrl,
      modelName: embedding.modelName,
      apiKey: (shareChatRagEndpoint.value ? chat.apiKey : embedding.apiKey).trim() || undefined,
    })
    if (data.data.success) ElMessage.success('RAG 连接成功')
    else ElMessage.error(data.data.message)
  } finally {
    ragTesting.value = false
  }
}

async function persistCard(card: EndpointCard, overrides: Partial<RuntimeEndpointRequest> = {}) {
  const { data } = await saveRuntimeEndpoint({
    mode: card.mode,
    capability: card.capability,
    baseUrl: overrides.baseUrl ?? card.baseUrl.trim(),
    modelName: overrides.modelName ?? card.modelName.trim(),
    apiKey: overrides.apiKey ?? (card.apiKey.trim() || undefined),
  })
  Object.assign(card, data.data, { apiKey: '' })
}

async function saveChatRag() {
  const chat = chatCard.value
  const embedding = embeddingCard.value
  if (!chat || !embedding) return
  if (!chat.baseUrl.trim() || !chat.modelName.trim() || !embedding.modelName.trim()) {
    ElMessage.warning('请填写 Chat Base URL、Chat 模型和 Embedding 模型名称')
    return
  }
  chatRagSaving.value = true
  try {
    const chatApiKey = chat.apiKey.trim() || undefined
    await persistCard(chat, { apiKey: chatApiKey })
    await persistCard(embedding, {
      baseUrl: shareChatRagEndpoint.value ? chat.baseUrl.trim() : embedding.baseUrl.trim(),
      apiKey: shareChatRagEndpoint.value ? chatApiKey : (embedding.apiKey.trim() || undefined),
    })
    ElMessage.success('Chat / RAG 配置已保存，运行模式需要重新校验')
    await refreshModeStatus()
  } finally {
    chatRagSaving.value = false
  }
}

async function saveCard(card: EndpointCard) {
  if (!card.baseUrl.trim() || !card.modelName.trim()) {
    ElMessage.warning('请填写 Base URL 和模型名称')
    return
  }
  card.saving = true
  try {
    const { data } = await saveRuntimeEndpoint({
      mode: card.mode,
      capability: card.capability,
      baseUrl: card.baseUrl.trim(),
      modelName: card.modelName.trim(),
      apiKey: card.apiKey.trim() || undefined,
    })
    Object.assign(card, data.data, { apiKey: '' })
    ElMessage.success(`${capabilityLabel(card.capability)} 配置已保存，运行模式需要重新校验`)
    await refreshModeStatus()
  } finally {
    card.saving = false
  }
}

async function removeCard(card: EndpointCard) {
  try {
    const warning = card.mode === 'API' && card.credentialProvider
      ? '删除后会同时删除该提供商共享的 API Key，相关能力将无法调用。'
      : '删除后将恢复环境变量或默认值。'
    await ElMessageBox.confirm(warning, `删除${capabilityLabel(card.capability)}配置`, { type: 'warning' })
    await deleteRuntimeEndpoint(card.mode, card.capability)
    ElMessage.success(`${capabilityLabel(card.capability)} 配置已删除`)
    await load()
  } catch {
    // 用户取消确认时不提示错误。
  }
}

async function refreshModeStatus() {
  const { data } = await getRuntimeModeStatus()
  modeStatus.value = data.data
}

function selectMode(mode: RuntimeMode) {
  selectedMode.value = mode
  syncChatRagSharing()
}

onMounted(load)
</script>

<template>
  <div class="pg settings">
    <header class="pg-head">
      <div>
        <h1 class="pg-title">模型配置</h1>
        <p class="pg-sub">统一配置 Chat / RAG 服务，Vision 保持独立端点；保存后可按能力测试连通性。</p>
      </div>
      <span v-if="loading" class="loading-text">读取中...</span>
    </header>

    <div class="settings-layout">
      <section class="pg-panel runtime-panel">
        <div class="runtime-head">
          <div>
            <p class="eyebrow">CURRENT RUNTIME</p>
            <h2 class="panel-title">当前运行模式</h2>
            <p class="panel-note">全局只会启用一个模式，切换前必须完成该模式的能力校验。</p>
          </div>
          <span class="mode-badge" :class="activeMode === selectedMode ? 'is-active' : ''">
            {{ modeLabel(activeMode) }}{{ activeMode === selectedMode ? ' · 当前' : '' }}
          </span>
        </div>

        <div class="mode-switch" role="tablist" aria-label="运行模式">
          <button v-for="mode in modes" :key="mode" class="mode-tab" :class="{ 'is-selected': selectedMode === mode }" @click="selectMode(mode)">
            <span>{{ modeLabel(mode) }}</span>
            <small>{{ modeStatus?.validatedModes?.[mode] ? '已校验' : '待校验' }}</small>
          </button>
        </div>

        <div class="runtime-status">
          <div>
            <span class="status-label">{{ modeLabel(selectedMode) }}最近校验</span>
            <strong>{{ formatDate(modeStatus?.validatedAt?.[selectedMode]) }}</strong>
          </div>
          <div class="status-capabilities">
            <span v-for="capability in capabilities" :key="capability" class="capability-state" :class="modeStatus?.capabilities?.[capability.toLowerCase()] === 'OK' ? 'is-ok' : ''">
              {{ capabilityLabel(capability) }}
              <b v-if="modeStatus?.capabilities?.[capability.toLowerCase()] === 'OK'">通过</b>
              <b v-else>—</b>
            </span>
          </div>
          <div class="runtime-actions">
            <button class="action-button secondary" :disabled="modeLoading" @click="validateSelectedMode">{{ modeLoading ? '校验中...' : '测试当前模式' }}</button>
            <button class="action-button primary" :disabled="modeLoading || !selectedModeValidated || activeMode === selectedMode" @click="enableSelectedMode">{{ activeMode === selectedMode ? '正在使用' : '启用该模式' }}</button>
          </div>
        </div>
      </section>

      <section class="config-section">
        <div class="section-heading">
          <div><p class="eyebrow">{{ selectedMode }}</p><h2 class="section-title">{{ modeLabel(selectedMode) }}端点</h2></div>
          <p class="section-note">配置来源为 <span class="source-chip">环境变量 / 默认值</span> 时，页面展示的是当前回退值。</p>
        </div>

        <div class="endpoint-grid">
          <article v-if="chatCard && embeddingCard" class="pg-panel endpoint-card chat-rag-card">
            <div class="endpoint-head">
              <div class="capability-icon">问</div>
              <div class="endpoint-title"><h3>Chat / RAG</h3><p>对话、摘要、标签生成与语义检索</p></div>
              <span class="source-chip" :class="{ 'is-custom': chatCard.source === 'CUSTOM' }">{{ chatCard.source === 'CUSTOM' ? '自定义' : '默认' }}</span>
            </div>
            <div class="field-grid">
              <label class="field"><span>Chat Base URL</span><input v-model="chatCard.baseUrl" type="url" placeholder="https://api.example.com/v1" /></label>
              <label class="field"><span>Chat 模型名称</span><input v-model="chatCard.modelName" type="text" placeholder="chat-model" /></label>
              <label class="field field-wide"><span>API Key <em v-if="chatCard.maskedApiKey">已保存 {{ chatCard.maskedApiKey }}</em></span><input v-model="chatCard.apiKey" type="password" autocomplete="new-password" :placeholder="chatCard.maskedApiKey ? '留空则沿用已保存密钥' : selectedMode === 'API' ? '输入 API Key' : '可选：本地服务密钥'" /></label>
            </div>

            <div class="rag-section">
              <div class="subsection-head">
                <div><strong>RAG 语义检索</strong><span class="optional-chip">可选</span><p>Embedding 负责查找相似文件，模型名称与 Chat 分开。</p></div>
                <div class="rag-heading-actions"><span class="source-chip" :class="{ 'is-custom': embeddingCard.source === 'CUSTOM' }">{{ embeddingCard.source === 'CUSTOM' ? '自定义' : '默认' }}</span><button class="link-button" type="button" @click="showRagAdvanced = !showRagAdvanced">{{ showRagAdvanced ? '收起高级配置' : '展开高级配置' }}</button></div>
              </div>
              <label class="shared-switch"><input v-model="shareChatRagEndpoint" type="checkbox" /><span>与 Chat 共用 Base URL 和 API Key</span></label>
              <div v-if="showRagAdvanced" class="field-grid rag-fields">
                <label class="field"><span>Embedding 模型名称</span><input v-model="embeddingCard.modelName" type="text" placeholder="embedding-model" /></label>
                <label v-if="!shareChatRagEndpoint" class="field"><span>Embedding Base URL</span><input v-model="embeddingCard.baseUrl" type="url" placeholder="https://api.example.com/v1" /></label>
                <label v-if="!shareChatRagEndpoint" class="field field-wide"><span>Embedding API Key <em v-if="embeddingCard.maskedApiKey">已保存 {{ embeddingCard.maskedApiKey }}</em></span><input v-model="embeddingCard.apiKey" type="password" autocomplete="new-password" :placeholder="embeddingCard.maskedApiKey ? '留空则沿用已保存密钥' : selectedMode === 'API' ? '输入 API Key' : '可选：本地服务密钥'" /></label>
              </div>
              <p v-if="!embeddingCard.embeddingEnabled" class="endpoint-note">RAG 当前未启用。保存服务配置后，还需在部署配置中启用 Embedding；未启用时系统继续使用关键词搜索。</p>
              <p v-else class="endpoint-note">RAG 已启用。首次更换 Embedding 模型后，请执行向量重建，避免旧向量与新模型不匹配。</p>
            </div>

            <div class="endpoint-actions">
              <button class="action-button secondary" :disabled="chatCard.testing" @click="testCard(chatCard)">{{ chatCard.testing ? '测试中...' : '测试 Chat' }}</button>
              <button class="action-button secondary" :disabled="ragTesting" @click="testRag">{{ ragTesting ? '测试中...' : '测试 RAG' }}</button>
              <button class="action-button primary" :disabled="chatRagSaving" @click="saveChatRag">{{ chatRagSaving ? '保存中...' : '保存 Chat / RAG' }}</button>
              <button class="action-button danger" :disabled="chatRagSaving" @click="removeCard(chatCard)">删除 Chat 配置</button>
              <button class="action-button danger" :disabled="chatRagSaving" @click="removeCard(embeddingCard)">删除 RAG 配置</button>
            </div>
          </article>

          <article v-if="visionCard" class="pg-panel endpoint-card">
            <div class="endpoint-head">
              <div class="capability-icon">视</div>
              <div class="endpoint-title"><h3>Vision</h3><p>图片与视觉内容理解</p></div>
              <span class="source-chip" :class="{ 'is-custom': visionCard.source === 'CUSTOM' }">{{ visionCard.source === 'CUSTOM' ? '自定义' : '默认' }}</span>
            </div>
            <div class="field-grid">
              <label class="field"><span>Base URL</span><input v-model="visionCard.baseUrl" type="url" placeholder="https://api.example.com/v1" /></label>
              <label class="field"><span>模型名称</span><input v-model="visionCard.modelName" type="text" placeholder="vision-model" /></label>
              <label class="field field-wide"><span>API Key <em v-if="visionCard.maskedApiKey">已保存 {{ visionCard.maskedApiKey }}</em></span><input v-model="visionCard.apiKey" type="password" autocomplete="new-password" :placeholder="visionCard.maskedApiKey ? '留空则沿用已保存密钥' : selectedMode === 'API' ? '输入 API Key' : '可选：本地服务密钥'" /></label>
            </div>
            <div class="endpoint-actions">
              <button class="action-button secondary" :disabled="visionCard.testing" @click="testCard(visionCard)">{{ visionCard.testing ? '测试中...' : '测试连接' }}</button>
              <button class="action-button primary" :disabled="visionCard.saving" @click="saveCard(visionCard)">{{ visionCard.saving ? '保存中...' : '保存配置' }}</button>
              <button class="action-button danger" :disabled="visionCard.saving" @click="removeCard(visionCard)">删除配置</button>
            </div>
          </article>
        </div>
        <p class="security-note">API Key 只在服务端加密保存，接口和页面仅展示脱敏结果；保存端点或密钥后，当前模式校验会自动失效。</p>
      </section>

      <section class="pg-panel about-panel">
        <h2 class="panel-title">关于 Coffer</h2>
        <div class="brand"><div class="brand-mark">智</div><div><h3 class="brand-name">Coffer · 智能文件管家</h3><p class="brand-desc">面向个人与团队的文件存储：AI 读取内容、自动归类打标，你只需确认标签，让检索像聊天一样自然。</p></div></div>
        <dl class="kv-list">
          <div class="kv"><dt class="kv-label">前端</dt><dd class="kv-value">Vue 3 · TypeScript · Vite · Element Plus</dd></div>
          <div class="kv"><dt class="kv-label">后端</dt><dd class="kv-value">Spring Boot · LangChain4j · MinIO</dd></div>
          <div class="kv"><dt class="kv-label">模式</dt><dd class="kv-value">本地单用户</dd></div>
        </dl>
      </section>
    </div>
  </div>
</template>

<style scoped>
.settings-layout { flex: 1; min-height: 0; display: flex; flex-direction: column; gap: var(--gap); overflow-y: auto; padding-bottom: 8px; }
.runtime-panel, .about-panel { flex: none; }
.runtime-head, .section-heading, .endpoint-head, .runtime-status { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.eyebrow { margin: 0 0 4px; color: var(--accent); font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.panel-title, .section-title { margin: 0; font-family: var(--font-display); font-size: 16px; color: var(--text-1); }
.section-title { font-size: 18px; }
.panel-note, .section-note, .loading-text { margin: 4px 0 0; font-size: 11.5px; color: var(--text-3); }
.mode-badge, .source-chip { flex: none; padding: 4px 10px; border-radius: var(--radius-pill); color: var(--text-3); background: var(--panel-3); font-size: 11px; }
.mode-badge.is-active, .source-chip.is-custom { color: var(--ok); background: rgb(var(--ok-rgb) / .13); }
.mode-switch { display: flex; gap: 8px; margin-top: 18px; padding: 4px; border-radius: var(--radius-inner); background: var(--panel-2); }
.mode-tab { flex: 1; display: flex; justify-content: center; align-items: baseline; gap: 8px; border: 0; border-radius: 9px; padding: 9px 12px; cursor: pointer; color: var(--text-2); background: transparent; }
.mode-tab small { font-size: 10px; color: var(--text-3); }.mode-tab.is-selected { color: var(--accent); background: var(--panel); box-shadow: 0 2px 8px rgb(0 0 0 / .05); }.mode-tab.is-selected small { color: var(--accent); }
.runtime-status { align-items: center; margin-top: 16px; padding-top: 15px; border-top: 1px dashed var(--line); }.status-label { display: block; color: var(--text-3); font-size: 11px; }.runtime-status strong { display: block; margin-top: 2px; color: var(--text-1); font-size: 12px; font-weight: 500; }.status-capabilities { display: flex; gap: 8px; flex-wrap: wrap; }.capability-state { padding: 4px 8px; border-radius: var(--radius-pill); color: var(--text-3); background: var(--panel-2); font-size: 11px; }.capability-state.is-ok { color: var(--ok); background: rgb(var(--ok-rgb) / .13); }.capability-state b { margin-left: 4px; font-weight: 700; }.runtime-actions, .endpoint-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
.config-section { flex: none; }.section-heading { align-items: end; margin: 0 4px 12px; }.section-note { text-align: right; }.endpoint-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr); gap: var(--gap); }.endpoint-card { min-width: 0; }.endpoint-head { align-items: center; }.capability-icon { flex: none; display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%; color: var(--accent); background: rgb(var(--accent-rgb) / .12); font-family: var(--font-display); font-size: 14px; }.endpoint-title { min-width: 0; flex: 1; }.endpoint-title h3 { margin: 0; color: var(--text-1); font-size: 15px; }.endpoint-title p { margin: 2px 0 0; color: var(--text-3); font-size: 11px; }.field-grid { display: grid; gap: 11px; margin-top: 18px; }.field { display: grid; gap: 5px; color: var(--text-3); font-size: 11px; }.field em { margin-left: 4px; color: var(--ok); font-style: normal; font-size: 10px; }.field input { width: 100%; min-width: 0; border: 1px solid var(--line-strong); border-radius: var(--radius-inner); padding: 9px 10px; outline: none; color: var(--text-1); background: var(--panel-2); font: inherit; }.field input:focus { border-color: var(--accent); }.rag-section { margin-top: 20px; padding-top: 16px; border-top: 1px dashed var(--line); }.subsection-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.subsection-head strong { color: var(--text-1); font-size: 12px; }.subsection-head p { margin: 3px 0 0; color: var(--text-3); font-size: 10.5px; line-height: 1.5; }.rag-heading-actions { display: flex; align-items: center; gap: 8px; flex: none; }.optional-chip { margin-left: 6px; padding: 2px 6px; border-radius: var(--radius-pill); color: var(--accent); background: rgb(var(--accent-rgb) / .1); font-size: 10px; }.link-button { border: 0; padding: 0; color: var(--accent); background: transparent; cursor: pointer; font: inherit; font-size: 10.5px; }.shared-switch { display: flex; align-items: center; gap: 7px; margin-top: 13px; color: var(--text-2); font-size: 11px; cursor: pointer; }.shared-switch input { accent-color: var(--accent); }.rag-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 12px; }.endpoint-note, .security-note { margin: 11px 0 0; color: var(--text-3); font-size: 10.5px; line-height: 1.5; }.endpoint-actions { margin-top: 16px; }.security-note { padding: 0 4px; }.action-button { border: 1px solid transparent; border-radius: var(--radius-inner); padding: 8px 11px; cursor: pointer; white-space: nowrap; color: var(--text-1); background: var(--panel-3); font: inherit; font-size: 11.5px; }.action-button:hover:not(:disabled) { border-color: var(--accent); }.action-button.primary { color: #fff; background: var(--accent); }.action-button.danger { color: var(--danger); }.action-button:disabled { opacity: .55; cursor: not-allowed; }
.brand { display: flex; gap: 14px; padding: 14px; margin: 14px 0 8px; border-radius: var(--radius-inner); background: var(--panel-3); }.brand-mark { flex: none; width: 48px; height: 48px; display: grid; place-items: center; border-radius: 50%; background: var(--accent); color: #fff; font-family: var(--font-display); font-size: 22px; }.brand-name { margin: 2px 0 6px; font-size: 15px; color: var(--text-1); }.brand-desc { margin: 0; font-size: 12px; line-height: 1.7; color: var(--text-3); }.kv-list { margin: 0; }.kv { display: flex; align-items: baseline; gap: 16px; padding: 11px 0; border-bottom: 1px dashed var(--line); }.kv:last-child { border-bottom: none; }.kv-label { flex: none; width: 52px; font-size: 12.5px; color: var(--text-3); }.kv-value { flex: 1; min-width: 0; margin: 0; font-size: 13px; color: var(--text-1); }
@media (max-width: 1100px) { .endpoint-grid { grid-template-columns: 1fr; } }.runtime-status { flex-wrap: wrap; } @media (max-width: 760px) { .endpoint-grid { grid-template-columns: 1fr; }.rag-fields { grid-template-columns: 1fr; }.section-heading { align-items: flex-start; flex-direction: column; }.section-note { text-align: left; }.runtime-status { align-items: flex-start; flex-direction: column; }.runtime-actions { justify-content: flex-start; } }
</style>
