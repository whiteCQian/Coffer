<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminEmbedding, saveAdminEmbedding, testAdminEmbedding, enableAdminEmbedding, clearAdminEmbedding, type AdminEmbeddingConfig } from '@/api/embedding'
import type { RuntimeMode } from '@/api/modelRuntime'
const saved = ref<AdminEmbeddingConfig | null>(null)
const draft = reactive({ mode: 'API' as RuntimeMode, baseUrl: '', modelName: '', dimensions: 1024, apiKey: '' })
const busy = ref(false), error = ref('')
const dirty = computed(() => !saved.value || draft.mode !== saved.value.mode || draft.baseUrl.trim() !== saved.value.baseUrl
  || draft.modelName.trim() !== saved.value.modelName || draft.dimensions !== saved.value.dimensions || Boolean(draft.apiKey.trim()))
function apply(config: AdminEmbeddingConfig) {
  saved.value = config
  Object.assign(draft, { mode: config.mode, baseUrl: config.baseUrl, modelName: config.modelName, dimensions: config.dimensions, apiKey: '' })
}
async function load() {
  busy.value = true; error.value = ''
  try { apply((await getAdminEmbedding()).data.data) }
  catch { error.value = '向量服务配置加载失败，请重新加载。' }
  finally { busy.value = false }
}
async function save() {
  if (!draft.baseUrl.trim() || !draft.modelName.trim()) { ElMessage.warning('请填写向量服务地址和模型名称'); return }
  busy.value = true
  try {
    apply((await saveAdminEmbedding({ ...draft, baseUrl: draft.baseUrl.trim(), modelName: draft.modelName.trim(), apiKey: draft.apiKey.trim() || undefined })).data.data)
    ElMessage.success('向量服务已保存，请先测试连接，再启用')
  } finally { busy.value = false }
}
async function test() {
  busy.value = true
  try {
    const result = (await testAdminEmbedding()).data.data
    apply(result.configuration)
    if (result.success) ElMessage.success('向量连接测试通过，可以启用')
    else ElMessage.error(result.message)
  } finally { busy.value = false }
}
async function toggle() {
  busy.value = true
  try { apply((await enableAdminEmbedding(!saved.value?.enabled)).data.data); ElMessage.success(saved.value?.enabled ? '语义向量检索已启用' : '语义向量检索已停用') }
  finally { busy.value = false }
}
async function clear() {
  try { await ElMessageBox.confirm('清除统一向量连接并停用语义向量检索？用户的 Chat 和 Vision 配置会保留。', '清除向量服务', { type: 'warning' }) }
  catch { return }
  busy.value = true
  try { apply((await clearAdminEmbedding()).data.data) } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <section class="pg-panel admin-embedding-panel">
    <h2>语义向量检索 · 管理员统一配置</h2>
    <p>所有普通用户共用此向量服务；各账号的文件和索引仍独立隔离。Chat 和 Vision 由用户自行配置。</p>
    <p v-if="error" role="alert">{{ error }} <button @click="load">重新加载</button></p>
    <p v-else>当前状态：{{ saved?.enabled ? '已启用' : '未启用，使用关键词 / 全文检索' }} · {{ saved?.validatedAt ? '连接已测试' : '连接待测试' }}</p>
    <div class="embedding-fields">
      <label>向量服务模式<select v-model="draft.mode"><option value="API">API 模式</option><option value="LOCAL">本地模式</option></select></label>
      <label>Embedding Base URL<input v-model="draft.baseUrl" type="url" placeholder="向量服务的 OpenAI 兼容地址" /></label>
      <label>Embedding 模型名称<input v-model="draft.modelName" placeholder="填写服务提供的向量模型名称" /></label>
      <label>向量维度<input v-model.number="draft.dimensions" type="number" min="1" max="8192" /></label>
      <label>Embedding API Key <small v-if="saved?.maskedApiKey">已保存 {{ saved.maskedApiKey }}</small><input v-model="draft.apiKey" type="password" autocomplete="new-password" :placeholder="saved?.maskedApiKey ? '留空沿用已保存 Key' : draft.mode === 'API' ? '填写该向量服务的 Key' : '可选：本地服务 Key'" /></label>
    </div>
    <p>先保存，再测试已保存的连接，最后启用。更换模型或维度会隔离旧索引；用户需重新确认目标并重建自己的向量索引。本地模式不会调用 API 模式的向量服务。</p>
    <div class="embedding-actions">
      <button :disabled="busy" @click="save">保存向量服务</button>
      <button :disabled="busy || dirty || !saved?.configured" @click="test">测试向量连接</button>
      <button :disabled="busy || dirty || (!saved?.enabled && !saved?.validatedAt)" @click="toggle">{{ saved?.enabled ? '停用语义检索' : '启用语义检索' }}</button>
      <button :disabled="busy" @click="clear">清除向量服务</button>
    </div>
  </section>
</template>
<style scoped>
.admin-embedding-panel { padding: 24px; margin: 20px 0; } p { line-height: 1.7; color: var(--text-3); }
.embedding-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
label { display: grid; gap: 8px; } input, select, button { padding: 10px; border: 1px solid var(--line); border-radius: 8px; color: var(--text-1); background: var(--panel-2); }
.embedding-actions { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 16px; } button { cursor: pointer; } button:disabled { opacity: .5; cursor: default; }
@media (max-width: 760px) { .embedding-fields { grid-template-columns: 1fr; } }
</style>
