<script setup lang="ts">
import { ref, watch } from 'vue'
import { confirmSavedFile, searchArchiveOperationItems, retryArchiveOperation } from '@/api/governance'
import type { FileDetailResponse } from '@/api/types'
import { taskStateLabel } from '@/utils/status'
const props = defineProps<{ file: FileDetailResponse }>()
const emit = defineEmits<{ changed: []; later: [] }>()
const tags = ref<string[]>([]), newTag = ref(''), name = ref(''), summary = ref(''), category = ref('OTHER')
const busy = ref(false), result = ref('')
watch(() => [props.file.id, props.file.revision], () => {
  tags.value = [...(props.file.confirmedTags ?? []), ...(props.file.pendingTags ?? [])].map(t => t.tagName)
  name.value = props.file.fileName; summary.value = props.file.summary ?? ''; category.value = props.file.category ?? 'OTHER'
  result.value = ''
}, { immediate: true })
function addTag() { const t = newTag.value.trim(); if (t && !tags.value.includes(t)) tags.value.push(t); newTag.value = '' }
async function submit() {
  if (busy.value) return
  busy.value = true; result.value = '正在提交，文件已保存…'
  try {
    addTag()
    if (props.file.taskState === 'ARCHIVE_FAILED') {
      const { data } = await searchArchiveOperationItems({ fileId: props.file.id, size: 1 })
      const item = data.data.content[0]
      if (!item) throw new Error('未找到原归档任务，请到最近操作核对')
      await retryArchiveOperation(item.batchId)
    } else await confirmSavedFile(props.file.id, { revision: props.file.revision, tags: tags.value,
      fileName: name.value, summary: summary.value, category: category.value })
    result.value = '已受理，正在归档；无需重复提交。'; emit('changed')
  } catch (error) { result.value = (error as Error).message || '文件已保存，归档未完成，请重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <section class="confirmation-card" aria-label="确认整理建议">
    <h3>{{ taskStateLabel(file) }}</h3>
    <p v-if="file.taskState === 'SAVED_NEEDS_ATTENTION'">文件已保存。AI 分析尚未完成或未获授权；可以按原名先归档，也可稍后整理。普通查找和下载仍可使用。</p>
    <p v-else>整组采用以下标签；可移除或新增。名称、摘要和分类无需填写。</p>
    <div class="tag-editor"><span v-for="(tag, index) in tags" :key="tag">{{ tag }} <button :disabled="busy" :aria-label="`移除标签 ${tag}`" @click="tags.splice(index, 1)">×</button></span></div>
    <form class="add-tag" @submit.prevent="addTag"><input v-model="newTag" :disabled="busy" placeholder="新增标签（可选）" maxlength="100" aria-label="新增标签" /><button :disabled="busy || tags.length >= 50">添加</button></form>
    <details><summary>修改名称、摘要和分类（可选）</summary>
      <label>名称<input v-model="name" maxlength="255" /></label>
      <label>摘要<textarea v-model="summary" maxlength="1000" /></label>
      <label>分类<select v-model="category"><option v-for="c in ['OTHER','CONTRACT','INVOICE','REPORT','ID','IMAGE','VIDEO']" :key="c" :value="c">{{ ({OTHER:'未分类',CONTRACT:'合同',INVOICE:'发票',REPORT:'报告',ID:'证件',IMAGE:'图片',VIDEO:'视频'})[c as 'OTHER'] }}</option></select></label>
    </details>
    <p v-if="['ARCHIVE_BLOCKED','ROLLING_BACK'].includes(file.taskState ?? '')">文件已保存。整理或撤销尚未结束，请在<RouterLink to="/operations">最近操作</RouterLink>查看结果或核对原因；普通查找和下载仍可用。</p><div class="confirm-actions"><button :disabled="busy || ['ARCHIVING','ARCHIVE_BLOCKED','ROLLING_BACK'].includes(file.taskState ?? '') || file.status === 'PROCESSING' || file.status === 'PENDING'" @click="submit">{{ busy ? '正在提交…' : file.taskState === 'ARCHIVE_FAILED' ? '重试归档' : '确认并归档' }}</button><button :disabled="busy" @click="emit('later')">稍后处理</button></div>
    <p role="status">{{ result || (file.taskState === 'ARCHIVING' ? '正在归档，结果会自动更新。文件仍可下载。' : '修改随“确认并归档”一起提交。') }}</p>
  </section>
</template>
<style scoped>
.confirmation-card { padding:16px; border:1px solid var(--line); border-radius:10px; margin:16px 0; font-size:13px; }
p { line-height:1.6; } .tag-editor,.add-tag,.confirm-actions { display:flex; flex-wrap:wrap; gap:8px; margin:12px 0; }
.tag-editor>span { background:var(--panel-3); border:1px solid var(--line); border-radius:15px; padding:4px 8px; }
button { padding:6px 9px; cursor:pointer; border:1px solid var(--line); border-radius:6px; background:var(--panel-2); color:var(--text-1); }
button:disabled { opacity:.5; cursor:default; } .confirm-actions>button:first-child { background:var(--accent); color:white; }
label { display:block; margin:10px 0; } input,textarea,select { width:100%; box-sizing:border-box; padding:8px; background:var(--panel); color:var(--text-1); border:1px solid var(--line); border-radius:6px; }
.add-tag input { flex:1; width:100px; } summary { cursor:pointer; } textarea { min-height:80px; }
</style>
