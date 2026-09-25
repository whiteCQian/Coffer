<script setup lang="ts">
import { Promotion } from '@element-plus/icons-vue'
import { computed, nextTick, onMounted, ref } from 'vue'

import { sendMessage } from '@/api/chat'
import { getModelCredentialStatus } from '@/api/modelCredentials'
import type { ChatCitation } from '@/api/types'
import FileDetailDrawer from '@/components/FileDetailDrawer.vue'

interface Msg {
  role: 'ai' | 'user'
  text: string
  citations: ChatCitation[]
}

type AiStatus = 'checking' | 'unconfigured' | 'configured' | 'connected' | 'failed'

const QUICK_PROMPTS = ['帮我找上个月的发票', '最近上传了什么', '待确认的文件有哪些']

const messages = ref<Msg[]>([])
const draft = ref('')
const sending = ref(false)
const sessionId = ref<string | undefined>(undefined)
const listEl = ref<HTMLElement>()
const aiStatus = ref<AiStatus>('checking')

const aiStatusLabel = computed(() => {
  switch (aiStatus.value) {
    case 'checking':
      return '检查 AI 状态…'
    case 'unconfigured':
      return 'AI 未配置'
    case 'configured':
      return 'AI 已配置'
    case 'connected':
      return 'AI 对话已接入'
    case 'failed':
      return 'AI 连接失败'
  }
})

/** 放大到页面中央（75%）：Teleport 到 body 固定定位；收起自动回侧栏原位 */
const expanded = ref(false)
/** 当前点击的引用文件；详情抽屉按 ID 重新读取真实文件状态。 */
const citationFileId = ref<number | null>(null)
/** 文件删除或详情加载失败后保留在对话卡片上的失效提示。 */
const invalidCitationIds = ref<Set<number>>(new Set())

/** 读取当前对话模型的配置状态；配置存在不等于网络连接成功。 */
async function loadAiStatus() {
  aiStatus.value = 'checking'
  try {
    const { data } = await getModelCredentialStatus()
    aiStatus.value = data.data.configured?.DEEPSEEK ? 'configured' : 'unconfigured'
  } catch {
    aiStatus.value = 'failed'
  }
}

function toggleExpand() {
  expanded.value = !expanded.value
}

function openCitation(citation: ChatCitation) {
  if (!citation.fileId || invalidCitationIds.value.has(citation.fileId)) return
  citationFileId.value = citation.fileId
}

function markCitationUnavailable(fileId: number) {
  invalidCitationIds.value = new Set(invalidCitationIds.value).add(fileId)
  if (citationFileId.value === fileId) citationFileId.value = null
}

function citationIsUnavailable(citation: ChatCitation) {
  return !citation.fileId || invalidCitationIds.value.has(citation.fileId)
}

function citationTypeLabel(type: string) {
  return type
    .split(',')
    .map((item) => ({
      KEYWORD: '关键词',
      VECTOR: '向量',
      HYBRID: '混合',
      RECENT_UPLOAD: '最近上传',
    })[item.trim()] ?? item.trim())
    .filter(Boolean)
    .join(' / ')
}

function citationScoreLabel(score: number) {
  return Number.isFinite(score) ? score.toFixed(3) : '—'
}

async function scrollBottom() {
  await nextTick()
  listEl.value?.scrollTo({ top: listEl.value.scrollHeight })
}

async function send(text?: string) {
  const content = (text ?? draft.value).trim()
  if (!content || sending.value) return
  draft.value = ''
  messages.value.push({ role: 'user', text: content, citations: [] })
  sending.value = true
  await scrollBottom()
  try {
    const { data } = await sendMessage({ message: content, sessionId: sessionId.value })
    sessionId.value = data.data.sessionId
    messages.value.push({
      role: 'ai',
      text: data.data.reply,
      citations: data.data.citations ?? [],
    })
    aiStatus.value = 'connected'
  } catch {
    /* 拦截器已弹错 */
    if (aiStatus.value !== 'unconfigured') {
      aiStatus.value = 'failed'
    }
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

onMounted(loadAiStatus)

function onQuick(p: string) {
  send(p)
}
</script>

<template>
  <Teleport :disabled="!expanded" to="body">
    <!-- 放大态遮罩：点空白处收起 -->
    <div v-if="expanded" class="chat-backdrop" @click.self="toggleExpand"></div>

    <aside class="chat-card" :class="{ 'is-expanded': expanded }">
      <div class="card-head">
        <h2 class="card-title">AI 管家</h2>
        <div class="head-right">
          <span class="chat-status" :class="`is-${aiStatus}`">
            <span class="status-dot" :class="`is-${aiStatus}`"></span>
            {{ aiStatusLabel }}
          </span>
          <!-- 右上角：放大到页面中央 / 收起还原（浅金迷你圆钮，无图标） -->
          <button
            class="expand-btn"
            :title="expanded ? '收起（回到侧栏）' : '放大到页面中央'"
            :aria-label="expanded ? '收起对话面板' : '放大对话到页面中央'"
            :aria-expanded="expanded"
            @click="toggleExpand"
          ></button>
        </div>
      </div>

      <div class="chat-guide">
        直接用自然语言问文件，比如「帮我找上个月的发票」。
      </div>

      <div ref="listEl" class="chat-messages">
        <div
          v-for="(m, i) in messages"
          :key="i"
          class="msg"
          :class="m.role === 'ai' ? 'is-ai' : 'is-user'"
        >
          <div class="msg-text">{{ m.text }}</div>

          <section v-if="m.role === 'ai' && m.citations.length" class="citation-section">
            <div class="citation-heading">引用来源</div>
            <div class="citation-list">
              <button
                v-for="citation in m.citations"
                :key="`${citation.fileId}-${citation.fileName}`"
                class="citation-card"
                :class="{ 'is-unavailable': citationIsUnavailable(citation) }"
                :disabled="citationIsUnavailable(citation)"
                type="button"
                @click="openCitation(citation)"
              >
                <span class="citation-file-icon">{{ (citation.fileType || 'file').toUpperCase().slice(0, 4) }}</span>
                <span class="citation-content">
                  <span class="citation-name">{{ citation.fileName || '未命名文件' }}</span>
                  <span v-if="citationIsUnavailable(citation)" class="citation-invalid">
                    引用失效或文件已删除
                  </span>
                  <span v-else class="citation-snippet">
                    {{ citation.snippet || '暂无命中片段' }}
                  </span>
                  <span class="citation-meta">
                    {{ citationTypeLabel(citation.retrievalType) }} · 相关度 {{ citationScoreLabel(citation.score) }}
                  </span>
                </span>
                <span v-if="!citationIsUnavailable(citation)" class="citation-arrow">›</span>
              </button>
            </div>
          </section>
        </div>

        <!-- 发送中占位 -->
        <div v-if="sending" class="msg is-ai is-typing">思考中…</div>

        <!-- 初始引导 -->
        <div v-if="!messages.length && !sending" class="chat-empty">
          还没有对话，点下方问题开始体验。
        </div>
      </div>

      <div class="chat-quick">
        <button
          v-for="q in QUICK_PROMPTS"
          :key="q"
          class="quick-chip"
          :disabled="sending"
          @click="onQuick(q)"
        >
          {{ q }}
        </button>
      </div>

      <div class="chat-input">
        <input
          v-model="draft"
          class="chat-input-field"
          type="text"
          placeholder="用自然语言描述你的需求…"
          :disabled="sending"
          @keyup.enter="send()"
        />
        <button class="chat-send" :disabled="sending" title="发送" @click="send()">
          <el-icon><Promotion /></el-icon>
        </button>
      </div>
    </aside>

    <FileDetailDrawer
      v-model="citationFileId"
      :elevated="expanded"
      @unavailable="markCitationUnavailable"
    />
  </Teleport>
</template>

<style scoped>
.chat-card {
  background: var(--chat);
  border-radius: var(--radius-card);
  padding: var(--pad);
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.card-title {
  color: #e8d8c8;
}
/* 头部右侧：状态 + 放大按钮（随卡片整体靠右） */
.head-right {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.chat-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-on-dark-2);
}
/* —— 放大/收起迷你圆钮：与旁边状态文字等高的浅金色珠——
   顶部微高光 + 底部内阴影塑形，外圈一道同色光晕；
   悬停轻抬、光晕加浓，按下回弹，聚焦带可见焦点环 */
.expand-btn {
  flex: none;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  background:
    radial-gradient(circle at 30% 24%, rgb(255 255 255 / 0.4), rgb(255 255 255 / 0) 50%),
    var(--chat-gold);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.5),
    inset 0 -2px 3px rgb(0 0 0 / 0.2),
    0 1px 2px rgb(0 0 0 / 0.22),
    0 0 0 3px rgb(var(--chat-gold-rgb) / 0.16);
  transition:
    transform 0.18s cubic-bezier(0.2, 0.8, 0.2, 1),
    box-shadow 0.18s ease,
    filter 0.18s ease;
}
.expand-btn:hover {
  transform: translateY(-1px) scale(1.08);
  filter: brightness(1.06);
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 0.55),
    inset 0 -2px 4px rgb(0 0 0 / 0.22),
    0 2px 5px rgb(0 0 0 / 0.28),
    0 0 0 4px rgb(var(--chat-gold-rgb) / 0.22);
}
.expand-btn:active {
  transform: translateY(0) scale(0.94);
  transition-duration: 0.08s;
}
.expand-btn:focus-visible {
  outline: 2px solid var(--chat-gold);
  outline-offset: 3px;
}
@media (prefers-reduced-motion: reduce) {
  .expand-btn {
    transition: none;
  }
}

/* —— 放大态：遮罩 + 面板铺到页面中央 75% —— */
.chat-backdrop {
  position: fixed;
  inset: 0;
  z-index: 200;
  background: rgb(5 5 8 / 0.55);
  animation: chat-fade 0.2s ease;
}
.chat-card.is-expanded {
  position: fixed;
  top: 12.5vh;
  left: 12.5vw;
  width: 75vw;
  height: 75vh;
  z-index: 201;
  border: 1px solid rgb(255 255 255 / 0.08);
  box-shadow: 0 24px 80px rgb(0 0 0 / 0.55);
  animation: chat-pop 0.24s cubic-bezier(0.2, 0.8, 0.2, 1);
}
@keyframes chat-fade {
  from {
    opacity: 0;
  }
}
@keyframes chat-pop {
  from {
    opacity: 0;
    transform: scale(0.96) translateY(10px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--chat-gold);
}
.status-dot.is-checking,
.status-dot.is-configured {
  background: var(--chat-gold);
}
.status-dot.is-connected {
  background: var(--ok);
}
.status-dot.is-unconfigured {
  background: rgb(232 216 200 / 0.45);
}
.status-dot.is-failed {
  background: #e7a6a0;
}
.chat-status.is-unconfigured,
.chat-status.is-failed {
  color: rgb(232 216 200 / 0.72);
}
.chat-guide {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: var(--radius-inner);
  background: rgb(255 255 255 / 0.06);
  color: rgb(232 216 200 / 0.7);
  font-size: 12px;
  line-height: 1.5;
}

.chat-messages {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
  padding: 12px 2px 2px;
}
.msg {
  max-width: 88%;
  padding: 10px 14px;
  border-radius: var(--radius-inner);
  font-size: 13px;
  line-height: 1.6;
  color: #ffffff;
  white-space: pre-line;
}
.msg-text {
  white-space: pre-line;
}
.msg.is-ai {
  align-self: flex-start;
  background: rgb(255 255 255 / 0.08);
  border-bottom-left-radius: 3px;
}
.msg.is-user {
  align-self: flex-end;
  background: var(--chat-user-bubble);
  color: #ffffff;
  border-bottom-right-radius: 3px;
}
.msg.is-typing {
  color: rgb(232 216 200 / 0.5);
  font-style: italic;
}

/* —— AI 回复引用 —— */
.citation-section {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid rgb(255 255 255 / 0.1);
}
.citation-heading {
  margin-bottom: 7px;
  color: rgb(232 216 200 / 0.62);
  font-size: 11px;
  letter-spacing: 0.04em;
}
.citation-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.citation-card {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 9px;
  border: 1px solid rgb(255 255 255 / 0.1);
  border-radius: 9px;
  background: rgb(0 0 0 / 0.12);
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}
.citation-card:hover:not(:disabled) {
  border-color: rgb(var(--chat-gold-rgb) / 0.62);
  background: rgb(255 255 255 / 0.08);
}
.citation-card:focus-visible {
  outline: 2px solid var(--chat-gold);
  outline-offset: 2px;
}
.citation-card.is-unavailable {
  cursor: default;
  opacity: 0.68;
}
.citation-file-icon {
  flex: none;
  min-width: 30px;
  padding: 4px 3px;
  border-radius: 5px;
  background: rgb(var(--chat-gold-rgb) / 0.16);
  color: var(--chat-gold);
  font-family: var(--font-mono);
  font-size: 9px;
  line-height: 1.1;
  text-align: center;
}
.citation-content {
  min-width: 0;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
}
.citation-name {
  overflow: hidden;
  color: #e8d8c8;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.citation-snippet,
.citation-invalid,
.citation-meta {
  font-size: 11px;
  line-height: 1.45;
}
.citation-snippet {
  display: -webkit-box;
  overflow: hidden;
  color: rgb(232 216 200 / 0.68);
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.citation-invalid {
  color: #e7a6a0;
}
.citation-meta {
  color: rgb(232 216 200 / 0.42);
}
.citation-arrow {
  flex: none;
  align-self: center;
  color: var(--chat-gold);
  font-size: 20px;
  line-height: 1;
}
.chat-empty {
  margin: auto;
  text-align: center;
  font-size: 12px;
  color: rgb(232 216 200 / 0.4);
}

.chat-quick {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 0;
}
.quick-chip {
  padding: 5px 12px;
  border: none;
  border-radius: var(--radius-pill);
  background: rgb(255 255 255 / 0.08);
  color: #b8a89a;
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
}
.quick-chip:hover:not(:disabled) {
  background: rgb(255 255 255 / 0.14);
  color: #e8d8c8;
}
.quick-chip:disabled {
  opacity: 0.5;
  cursor: default;
}

.chat-input {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid rgb(255 255 255 / 0.08);
}
.chat-input-field {
  flex: 1;
  min-width: 0;
  height: 40px;
  padding: 0 14px;
  border: none;
  border-radius: var(--radius-inner);
  background: rgb(255 255 255 / 0.08);
  color: #e8d8c8;
  font-size: 13px;
  font-family: inherit;
  outline: none;
}
.chat-input-field::placeholder {
  color: #6d665e;
}
.chat-input-field:focus {
  background: rgb(255 255 255 / 0.12);
}
.chat-input-field:disabled {
  opacity: 0.6;
}
.chat-send {
  flex: none;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: var(--accent);
  color: #fff;
  font-size: 17px;
  cursor: pointer;
}
.chat-send:hover:not(:disabled) {
  background: var(--accent-hover);
}
.chat-send:disabled {
  opacity: 0.6;
  cursor: default;
}
</style>
