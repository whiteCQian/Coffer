<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { runtimeStatus, runtimeLabels, runtimeActions, type RuntimeSnapshot } from '@/api/runtime'
const status = ref<RuntimeSnapshot | null>(null)
const unavailable = ref(false)
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
let loading = false
async function load() {
  if (loading) return
  loading = true
  try { const response = await runtimeStatus(); if (!disposed) { status.value = response.data.data; unavailable.value = false } }
  catch { if (!disposed) { unavailable.value = true; status.value = null } }
  finally { loading = false }
}
onMounted(() => { void load(); timer = setInterval(() => void load(), 30000) })
onUnmounted(() => { disposed = true; if (timer) clearInterval(timer) })
</script>
<template>
  <aside v-if="unavailable || (status && status.readiness !== 'UP')" class="runtime-banner" role="alert">
    <strong>{{ unavailable ? '暂时无法获取服务状态' : '部分服务暂时不可用' }}</strong>
    <p v-if="unavailable">检查网络连接，稍后重试；当前操作可能尚未完成。</p>
    <p v-for="component in status?.components.filter(c => c.status === 'DOWN') ?? []" :key="component.name">
      {{ runtimeLabels[component.name] }}：{{ runtimeLabels[component.reason] ?? '状态未核实' }}。{{ runtimeActions[component.action] }}
    </p>
    <p v-if="status?.readiness === 'OUT_OF_SERVICE'">状态采样已过期，请联系管理员检查后台服务。</p>
    <button @click="load">重新检查</button>
  </aside>
</template>
<style scoped>.runtime-banner { position: fixed; z-index: 2000; right: 20px; bottom: 20px; max-width: 440px; padding: 16px; border: 1px solid #dba14b; border-radius: 12px; background: var(--panel); color: var(--text-1); box-shadow: 0 4px 20px #0002; } p { margin: 8px 0; font-size: 13px; } button { cursor: pointer; }</style>
