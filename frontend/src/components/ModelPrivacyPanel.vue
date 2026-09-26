<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import type { Result } from '@/api/types'
import type { ModelTarget } from '@/api/modelConsent'

type Diagnostic = { id: number; time: string; status: string; totalTokens: number; durationMs: number; errorCategory: string | null }
const inbox = ref<ModelTarget | null>(null)
const diagnostics = ref<Diagnostic[]>([])
const page = ref(0)
const totalPages = ref(0)
const busy = ref(false)
async function load() {
  const [grant, logs] = await Promise.all([
    http.get<Result<ModelTarget | null>>('/model-execution/inbox'),
    http.get<Result<{ content: Diagnostic[]; totalPages: number }>>('/model-diagnostics', { params: { page: page.value } }),
  ])
  inbox.value = grant.data.data
  diagnostics.value = logs.data.data.content
  totalPages.value = logs.data.data.totalPages
}
async function authorize() {
  busy.value = true
  try { await http.post('/model-execution/inbox'); await load() }
  catch { /* 拦截器提示错误；用户可取消。 */ }
  finally { busy.value = false }
}
async function revoke() { await http.delete('/model-execution/inbox'); await load() }
async function deleteLogs() {
  await ElMessageBox.confirm('删除当前账号的全部模型诊断记录？', '删除诊断', { type: 'warning' })
  await http.delete('/model-diagnostics'); page.value = 0; await load()
}
async function rewrap() {
  await http.post('/model-execution/rewrap-secrets')
  ElMessage.success('你的模型密钥与任务配置已使用当前部署密钥重新加密')
}
async function turnPage(delta: number) { page.value += delta; await load() }
onMounted(load)
</script>

<template>
  <section class="pg-panel privacy-panel">
    <h2>AI 内容发送与诊断</h2>
    <p>模型调用默认不记录正文、对话、密钥或原始异常。诊断只包含用量、时间和结果，保留 30 天，可随时删除。</p>
    <h3>收件箱自动导入授权</h3>
    <p v-if="!inbox">尚未授权：收件箱文件不会自动发送给模型。</p>
    <template v-else>
      <p>已授权 {{ inbox.mode }} 模式，后续导入固定使用以下目标。修改模型设置不会改变此授权；重新授权后才影响新导入。</p>
      <ul><li v-for="target in inbox.targets" :key="target.capability">{{ target.capability }}：{{ target.baseUrl }} · {{ target.modelName }}</li></ul>
    </template>
    <el-button :loading="busy" @click="authorize">{{ inbox ? '重新确认自动导入目标' : '授权自动导入' }}</el-button>
    <el-button v-if="inbox" @click="revoke">撤销后续自动导入</el-button>
    <h3>我的模型诊断</h3>
    <el-button @click="load">刷新</el-button><el-button type="danger" plain @click="deleteLogs">删除全部诊断</el-button>
    <el-table :data="diagnostics" empty-text="暂无诊断记录">
      <el-table-column prop="time" label="时间" />
      <el-table-column prop="status" label="结果" />
      <el-table-column prop="totalTokens" label="Token" />
      <el-table-column prop="durationMs" label="耗时 ms" />
      <el-table-column prop="errorCategory" label="错误类别" />
    </el-table>
    <el-button :disabled="page === 0" @click="turnPage(-1)">上一页</el-button>
    <span>{{ page + 1 }} / {{ Math.max(1, totalPages) }}</span>
    <el-button :disabled="page + 1 >= totalPages" @click="turnPage(1)">下一页</el-button>
    <h3>密钥维护</h3>
    <p>部署管理员轮换加密主密钥后，可在这里更新当前账号的密文；模型发送目标和 API 密钥内容保持不变。</p>
    <el-button @click="rewrap">重新加密我的模型凭据</el-button>
  </section>
</template>
<style scoped>
.privacy-panel { flex: none; padding: 24px; } p, li { line-height: 1.7; overflow-wrap: anywhere; } h3 { margin-top: 24px; }
</style>
