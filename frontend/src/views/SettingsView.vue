<script setup lang="ts">
import CofferLogo from '@/components/CofferLogo.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AccountPanel from '@/components/AccountPanel.vue'
import DesktopBackupPanel from '@/components/DesktopBackupPanel.vue'
import { desktopBridge } from '@/api/desktop'
import { useAuthStore } from '@/stores/auth'
import ModelPrivacyPanel from '@/components/ModelPrivacyPanel.vue'
import PrivacyPanel from '@/components/PrivacyPanel.vue'
import { getEmbeddingStatus, type EmbeddingStatus } from '@/api/embedding'
import http from '@/api/http'
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
  type RuntimeMode,
  type RuntimeModeStatus,
} from '@/api/modelRuntime'

type EndpointCard = RuntimeEndpointConfig & {
  apiKey: string
  saving: boolean
  testing: boolean
}

const modes: RuntimeMode[] = ['API', 'LOCAL']
const capabilities: RuntimeCapability[] = ['CHAT', 'VISION']
const capabilityMeta: Record<RuntimeCapability, { title: string; description: string; icon: string }> = {
  CHAT: { title: 'Chat', description: '对话、摘要和标签生成', icon: '对' },
  VISION: { title: 'Vision', description: '图片与视觉内容理解', icon: '视' },
  EMBEDDING: { title: 'Embedding', description: '向量检索与语义匹配', icon: '向' },
}

const loading = ref(true)
const loadError = ref('')
const auth = useAuthStore()
const settingsTab = ref('models')
const settingsTabs = [{ id: 'models', label: '模型连接' }, { id: 'account', label: '账号与安全' }, { id: 'privacy', label: '数据与隐私' }, { id: 'about', label: '关于' }]
const modeLoading = ref(false)
const selectedMode = ref<RuntimeMode>('API')
const modeStatus = ref<RuntimeModeStatus | null>(null)
const cards = reactive<EndpointCard[]>([])

const activeCards = computed(() => cards.filter((card) => card.mode === selectedMode.value))
const selectedModeValidated = computed(() => Boolean(modeStatus.value?.validatedModes?.[selectedMode.value]))
const activeMode = computed(() => modeStatus.value?.activeMode ?? 'API')
const chatCard = computed(() => activeCards.value.find((card) => card.capability === 'CHAT'))
const embeddingStatus = ref<EmbeddingStatus | null>(null)
const reindexing = ref(false)
const visionCard = computed(() => activeCards.value.find((card) => card.capability === 'VISION'))

function cardFor(capability: RuntimeCapability) {
  return activeCards.value.find((card) => card.capability === capability)
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
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [statusResponse, configResponse] = await Promise.all([
      getRuntimeModeStatus(),
      getRuntimeEndpointConfigs(),
    ])
    modeStatus.value = statusResponse.data.data
    selectedMode.value = statusResponse.data.data.activeMode
    applyConfigs(configResponse.data.data)
    await loadEmbeddingStatus()
  } catch {
    loadError.value = '模型连接配置加载失败，请重新加载。'
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
  if (card.mode === 'API' && !(card.apiKey.trim() || card.maskedApiKey)) {
    ElMessage.warning(`${capabilityLabel(card.capability)} 尚未配置 Key，请填写该服务对应的 API Key 后再测试。`)
    return
  }
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
    const warning = card.mode === 'API' && card.capability === 'EMBEDDING'
      ? '删除后将恢复 Embedding 的默认端点和默认密钥来源。Vision 配置会保留。'
      : card.mode === 'API' && card.credentialProvider
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
  void loadEmbeddingStatus()
}

async function loadEmbeddingStatus() {
  const mode = selectedMode.value
  try { const { data } = await getEmbeddingStatus(mode); if (selectedMode.value === mode) embeddingStatus.value = data.data }
  catch { if (selectedMode.value === mode) embeddingStatus.value = null }
}
async function reindexVectors() {
  reindexing.value = true
  try {
    const { data } = await http.post<{ code: number; data: { jobId: string } }>('/vector/reindex')
    ElMessage.success(`个人向量重建任务已提交：${data.data.jobId}`)
  } finally { reindexing.value = false }
}

onMounted(load)
</script>

<template>
  <div class="pg settings">
    <header class="pg-head">
      <div>
        <h1 class="pg-title">{{ auth.isPersonal ? '模型配置' : '设置' }}</h1>
        <p class="pg-sub">{{ auth.isPersonal ? '自选回答模型与 Vision 服务。语义向量服务由管理员统一配置。' : '管理模型连接、账号安全与数据隐私。' }}</p>
      </div>
      <span v-if="loading" class="loading-text">读取中...</span>
    </header>

    <nav v-if="!auth.isPersonal" class="web-settings-tabs" role="tablist" aria-label="设置分类"><button v-for="tab in settingsTabs" :id="`settings-tab-${tab.id}`" :key="tab.id" role="tab" :aria-selected="settingsTab === tab.id" aria-controls="web-settings-panel" :class="{ active: settingsTab === tab.id }" @click="settingsTab = tab.id">{{ tab.label }}</button></nav>
    <div :id="auth.isPersonal ? undefined : 'web-settings-panel'" class="settings-layout" :role="auth.isPersonal ? undefined : 'tabpanel'" :aria-labelledby="auth.isPersonal ? undefined : `settings-tab-${settingsTab}`">
      <section v-show="auth.isPersonal || settingsTab === 'models'" class="pg-panel runtime-panel">
        <div class="runtime-head">
          <div>
            <p class="eyebrow">CURRENT RUNTIME</p>
            <h2 class="panel-title">当前运行模式</h2>
            <p class="panel-note">当前账号启用一个模式，切换前必须完成能力校验。已提交任务继续使用原先确认的配置。</p>
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
              <b v-else-if="modeStatus?.capabilities?.[capability.toLowerCase()] === 'DISABLED'">未启用</b>
              <b v-else-if="modeStatus?.capabilities?.[capability.toLowerCase()]">未通过</b>
              <b v-else>待测试</b>
            </span>
          </div>
          <div class="runtime-actions">
            <button class="action-button secondary" :disabled="modeLoading" @click="validateSelectedMode">{{ modeLoading ? '校验中...' : '测试当前模式' }}</button>
            <button class="action-button primary" :disabled="modeLoading || !selectedModeValidated || activeMode === selectedMode" @click="enableSelectedMode">{{ activeMode === selectedMode ? '正在使用' : '启用该模式' }}</button>
          </div>
        </div>
        <p class="panel-note">
          新任务当前使用{{ modeLabel(activeMode) }}{{ modeStatus?.currentModeValidated ? '（已校验）' : '（待校验）' }}。
          保存 Chat 和 Vision 配置后，请点击“测试当前模式”；切换模式还需点击“启用该模式”。单独测试 Chat 成功不会启用整个模式。
        </p>
      </section>

      <section v-show="auth.isPersonal || settingsTab === 'models'" class="config-section">
        <div class="section-heading">
          <div><p class="eyebrow">{{ selectedMode }}</p><h2 class="section-title">{{ modeLabel(selectedMode) }}端点</h2></div>
          <p class="section-note">配置来源为 <span class="source-chip">环境变量 / 默认值</span> 时，页面展示的是当前回退值。</p>
        </div>

        <div v-if="loadError" class="pg-panel settings-load-error" role="alert">
          <p>{{ loadError }}</p>
          <button class="action-button secondary" :disabled="loading" @click="load">重新加载</button>
        </div>

        <div class="endpoint-grid">
          <article v-if="chatCard" class="pg-panel endpoint-card chat-rag-card">
            <div class="endpoint-head">
              <div class="capability-icon">问</div>
              <div class="endpoint-title"><h3>RAG 回答 / Chat 模型</h3><p>由你选择检索后回答、对话、摘要和标签生成使用的模型</p></div>
              <span class="source-chip" :class="{ 'is-custom': chatCard.source === 'CUSTOM' }">{{ chatCard.source === 'CUSTOM' ? '自定义' : '默认' }}</span>
            </div>
            <div class="field-grid">
              <label class="field"><span>Chat Base URL</span><input v-model="chatCard.baseUrl" type="url" placeholder="https://api.example.com/v1" /></label>
              <label class="field"><span>回答 / Chat 模型名称</span><input v-model="chatCard.modelName" type="text" list="chat-model-suggestions" placeholder="例如 deepseek-flash，可自定义" /></label>
              <label class="field field-wide"><span>API Key <em v-if="chatCard.maskedApiKey">已保存 {{ chatCard.maskedApiKey }}</em></span><input v-model="chatCard.apiKey" type="password" autocomplete="new-password" :placeholder="chatCard.maskedApiKey ? '留空则沿用已保存密钥' : selectedMode === 'API' ? '输入 API Key' : '可选：本地服务密钥'" /></label>
            </div>
            <datalist id="chat-model-suggestions"><option value="deepseek-flash" /><option value="deepseek-v4-pro" /></datalist>
            <p class="endpoint-note">RAG 的回答模型目前与 Chat 共用这份配置，可填写 DeepSeek 或其他兼容服务支持的模型。保存回答模型不需要填写或测试 Embedding。</p>
            <div class="endpoint-actions">
              <button class="action-button secondary" :disabled="chatCard.testing" @click="testCard(chatCard)">{{ chatCard.testing ? '测试中...' : '测试回答模型' }}</button>
              <button class="action-button primary" :disabled="chatCard.saving" @click="saveCard(chatCard)">{{ chatCard.saving ? '保存中...' : '保存回答模型' }}</button>
              <button class="action-button danger" :disabled="chatCard.saving" @click="removeCard(chatCard)">删除 Chat 配置</button>
            </div>

            <div class="rag-section">
              <strong>语义向量检索 · 管理员统一配置</strong>
              <p class="endpoint-note">{{ embeddingStatus?.message || '向量服务状态暂时不可用，请稍后重新加载。' }}</p>
              <p v-if="embeddingStatus?.configured" class="endpoint-note">向量模型：{{ embeddingStatus.modelName }} · {{ embeddingStatus.dimensions }} 维。无需填写或测试个人向量 Key。</p>
              <button v-if="embeddingStatus?.available" class="action-button secondary" :disabled="reindexing || !modeStatus?.currentModeValidated || selectedMode !== activeMode" @click="reindexVectors">{{ reindexing ? '提交中...' : '重建我的向量索引' }}</button>
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

      <section v-show="auth.isPersonal || settingsTab === 'about'" class="pg-panel about-panel">
        <h2 class="panel-title">关于 Coffer</h2>
        <div class="brand"><CofferLogo style="--coffer-logo-size:48px"/><div><h3 class="brand-name">Coffer · 智能文件管家</h3><p class="brand-desc">{{ desktopBridge ? '管理自己的本机文件库：AI 读取内容、自动归类打标，让检索像聊天一样自然。' : '面向个人与团队的文件存储：AI 读取内容、自动归类打标，你只需确认标签，让检索像聊天一样自然。' }}</p></div></div>
        <dl class="kv-list">
          <div class="kv"><dt class="kv-label">前端</dt><dd class="kv-value">Vue 3 · TypeScript · Vite · Element Plus</dd></div>
          <div class="kv"><dt class="kv-label">后端</dt><dd class="kv-value">{{ desktopBridge ? 'Spring Boot · LangChain4j · H2 · 本地文件库' : 'Spring Boot · LangChain4j · MinIO' }}</dd></div>
          <div class="kv"><dt class="kv-label">模式</dt><dd class="kv-value">{{ desktopBridge ? '本机个人文件库（无需账号）' : '多账号私有工作区' }}</dd></div>
        </dl>
      </section>
      <AccountPanel v-if="!desktopBridge" v-show="auth.isPersonal || settingsTab === 'account'" />
      <DesktopBackupPanel v-else />
      <ModelPrivacyPanel v-show="auth.isPersonal || settingsTab === 'privacy'" />
      <PrivacyPanel v-show="auth.isPersonal || settingsTab === 'privacy'" />
    </div>
  </div>
</template>

<style scoped>
.web-settings-tabs { display: flex; gap: 24px; border-bottom: 1px solid var(--line); flex: none; }
.web-settings-tabs button { border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--text-2); font-size: 12px; padding: 10px 0; cursor: pointer; }.web-settings-tabs button.active { color: var(--accent); border-bottom-color: var(--accent); font-weight: 600; }
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
.brand { display: flex; gap: 14px; padding: 14px; margin: 14px 0 8px; border-radius: var(--radius-inner); background: var(--panel-3); }.brand-name { margin: 2px 0 6px; font-size: 15px; color: var(--text-1); }.brand-desc { margin: 0; font-size: 12px; line-height: 1.7; color: var(--text-3); }.kv-list { margin: 0; }.kv { display: flex; align-items: baseline; gap: 16px; padding: 11px 0; border-bottom: 1px dashed var(--line); }.kv:last-child { border-bottom: none; }.kv-label { flex: none; width: 52px; font-size: 12.5px; color: var(--text-3); }.kv-value { flex: 1; min-width: 0; margin: 0; font-size: 13px; color: var(--text-1); }
@media (max-width: 1100px) { .endpoint-grid { grid-template-columns: 1fr; } }.runtime-status { flex-wrap: wrap; } @media (max-width: 760px) { .endpoint-grid { grid-template-columns: 1fr; }.rag-fields { grid-template-columns: 1fr; }.section-heading { align-items: flex-start; flex-direction: column; }.section-note { text-align: left; }.runtime-status { align-items: flex-start; flex-direction: column; }.runtime-actions { justify-content: flex-start; } }
</style>
