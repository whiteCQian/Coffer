<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { ref } from 'vue'
import { useWebUploadsStore } from '@/stores/webUploads'
import { useAuthStore } from '@/stores/auth'

import { uploadFile, saveFileOnly } from '@/api/files'
import { UploadFilled } from '@element-plus/icons-vue'
import DesktopImportPanel from '@/components/DesktopImportPanel.vue'
import { desktopBridge } from '@/api/desktop'
import { createRequestId } from '@/utils/requestId'

const uploads = useWebUploadsStore(), auth = useAuthStore()
const emit = defineEmits<{ (e: 'uploaded'): void }>()
const props = withDefaults(defineProps<{ compact?: boolean; allowSaveOnly?: boolean }>(), { allowSaveOnly: true })
defineExpose({ pick: pickFile })
const inputRef = ref<HTMLInputElement>()
const uploading = ref(false)
const lastUpload = ref<{ signature: string; requestId: string } | null>(null)

function pickFile() {
  inputRef.value?.click()
}

async function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await upload(file)
}
async function onDrop(event: DragEvent) {
  const file = event.dataTransfer?.files[0]
  if (file) await upload(file)
}
async function upload(file: File) {
  if (uploading.value) return
  if (file.size > 32 * 1024 * 1024) { ElMessage.warning('单个文件不能超过 32MB'); return }
  uploading.value = true
  const generation = auth.generation
  let activeKey: string | undefined
  try {
    const fd = new FormData()
    fd.append('file', file)
    const signature = `${file.name}\0${file.size}\0${file.lastModified}`
    if (lastUpload.value?.signature !== signature) {
      lastUpload.value = { signature, requestId: createRequestId() }
    }
    const key = lastUpload.value.requestId
    activeKey = key
    uploads.begin(key, file.name)
    let receipt, savedOnly = false
    try { receipt = await uploadFile(fd, key, percent => { if (generation === auth.generation) uploads.progress(key, percent) }) }
    catch (error) {
      if (props.allowSaveOnly === false) throw error
      if (generation !== auth.generation) return
      await ElMessageBox.confirm('AI 暂不可用或尚未授权。可以先安全保存，之后再整理；普通查找和下载仍可用。', '仅保存文件', {confirmButtonText:'仅保存',cancelButtonText:'返回'})
      if (generation !== auth.generation) return
      receipt = await saveFileOnly(fd, key); savedOnly = true
    }
    if (generation !== auth.generation) return
    uploads.accepted(key, receipt.data.data, savedOnly)
    lastUpload.value = null
    ElMessage.success(savedOnly ? `「${file.name}」已保存，可稍后分析` : `「${file.name}」已上传，请在下方查看处理结果`)
    emit('uploaded')
  } catch (error) {
    if (generation === auth.generation) {
      if (activeKey) uploads.failed(activeKey)
      else ElMessage.error(error instanceof Error ? error.message : '上传初始化失败，请重新选择文件')
    }
    /* 拦截器已弹错 */
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <DesktopImportPanel v-if="desktopBridge" />
  <section v-else class="upload-zone" :class="{ compact: props.compact }" @dragover.prevent @drop.prevent="onDrop">
    <div class="uz-inner">
      <div class="uz-main">
        <span v-if="props.compact" class="upload-symbol"><el-icon><UploadFilled /></el-icon></span>
        <div><p class="uz-title">{{ props.compact ? '把文件交给你的 AI 管家' : '拖拽文件到此处，或' }}</p><p v-if="props.compact" class="uz-description">拖入文档或图片，AI 帮你提取摘要、建议标签。</p></div>
        <button class="uz-btn" :disabled="uploading" @click="pickFile">
          {{ uploading ? '上传中…' : '上传文件' }}
        </button>
        <input
          ref="inputRef"
          class="uz-file"
          type="file"
          hidden
          @change="onPicked"
        />
      </div>
      <p class="uz-hint">单文件 ≤ 32MB，AI 自动分类、打标、摘要</p>
    </div>
  </section>
</template>

<style scoped>
.upload-zone {
  background: var(--upload);
  border-radius: var(--radius-card);
  padding: var(--pad);
  min-width: 0;
  min-height: 0;
}
.uz-inner {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 20px 28px;
  border: 1.5px dashed rgb(var(--ink-teal-rgb) / 0.35);
  border-radius: var(--radius-inner);
}
.uz-main {
  display: flex;
  align-items: center;
  gap: 16px;
}
.uz-title {
  margin: 0;
  color: var(--ink-teal);
  font-size: 15px;
}
.uz-btn {
  padding: 10px 22px;
  border: none;
  border-radius: var(--radius-inner);
  background: var(--card-btn-bg);
  color: var(--ink-teal);
  font-size: 14px;
  font-family: inherit;
  cursor: pointer;
}
.uz-btn:hover {
  background: var(--card-btn-bg-hover);
}
.uz-btn:disabled {
  opacity: 0.65;
  cursor: default;
}
.uz-hint {
  margin: 0;
  color: rgb(var(--ink-teal-rgb) / 0.7);
  font-size: 12.5px;
}
.uz-file {
  display: none;
}
</style>

<style scoped>
.upload-zone.compact { padding: 0; border: 1px dashed rgb(var(--accent-rgb) / .3); background: rgb(var(--accent-rgb) / .04); border-radius: 10px; flex: none; }
.compact .uz-inner { border: 0; padding: 19px 22px; gap: 6px; align-items: stretch; }
.compact .uz-main { gap: 14px; }.compact .uz-title { font-size: 14px; font-weight: 600; color: var(--text-1); }.compact .uz-description { margin: 4px 0 0; color: var(--text-2); font-size: 11px; }.compact .uz-btn { margin-left: auto; flex-shrink: 0; background: var(--accent); color: white; font-size: 12px; padding: 8px 14px; border-radius: 7px; }.compact .uz-hint { margin-left: 52px; font-size: 10px; color: var(--text-3); }
.upload-symbol { display: grid; place-items: center; flex-shrink: 0; width: 38px; height: 38px; border-radius: 9px; color: var(--accent); background: rgb(var(--accent-rgb) / .1); font-size: 22px; }
@media (max-width: 540px) { .compact .uz-inner { padding: 18px 14px; }.compact .uz-main { flex-wrap: wrap; }.compact .uz-main > div { flex: 1; min-width: 160px; }.compact .uz-btn { margin: 0 0 0 52px; }.compact .uz-description { max-width: 230px; }.compact .uz-hint { margin-top: 4px; } }
</style>
