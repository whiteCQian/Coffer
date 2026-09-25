<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { uploadFile } from '@/api/files'

const router = useRouter()
const inputRef = ref<HTMLInputElement>()
const uploading = ref(false)

function pickFile() {
  inputRef.value?.click()
}

async function onPicked(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || uploading.value) return
  uploading.value = true
  const fd = new FormData()
  fd.append('file', file)
  try {
    await uploadFile(fd)
    ElMessage.success(`「${file.name}」已上传，AI 开始解析…`)
    // 首页其余模块仍走 mock，跳「全部文件」页查看真实解析进度
    router.push({ name: 'files' })
  } catch {
    /* 拦截器已弹错 */
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <section class="upload-zone">
    <div class="uz-inner">
      <div class="uz-main">
        <p class="uz-title">拖拽文件到此处，或</p>
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
      <p class="uz-hint">单文件 ≤ 50MB，AI 自动分类、打标、摘要</p>
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
