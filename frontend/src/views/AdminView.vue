<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AccountPanel from '@/components/AccountPanel.vue'
import http from '@/api/http'
import type { Result } from '@/api/types'
const counts = ref<Record<string, number>>({})
const labels: Record<string, string> = { pendingTasks: '排队任务', processingTasks: '处理中任务', failedTasks: '失败任务', pendingCompensations: '待补偿任务', pendingDeletions: '待清理对象' }
onMounted(async () => { counts.value = (await http.get<Result<Record<string, number>>>('/admin/operations')).data.data })
</script>
<template>
  <div class="admin-page">
    <h1>管理控制台</h1>
    <p>管理账号与查看运行数量。用户文件、对话和模型凭据仅向各自账号开放。</p>
    <section class="pg-panel counts"><div v-for="(value, name) in counts" :key="name"><strong>{{ value }}</strong><span>{{ labels[name] }}</span></div></section>
    <AccountPanel />
  </div>
</template>
<style scoped>
.admin-page { height: 100%; overflow: auto; padding: 20px; } .counts { display: flex; gap: 32px; margin: 24px 0; padding: 24px; } .counts div { display: grid; gap: 10px; } strong { font-size: 26px; }
</style>
