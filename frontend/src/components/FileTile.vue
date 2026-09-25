<script setup lang="ts">
import { computed } from 'vue'

/**
 * 文件类型图章：彩色圆形 + 扩展名。
 * 用于最近上传行 / 进度面板 / 文件卡片 / 详情抽屉，保证同一套“图章”语言。
 */
const props = withDefaults(
  defineProps<{
    fileType?: string | null
    /** sm 28 / md 40 / lg 52 */
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { fileType: null, size: 'md' },
)

const EXT_COLOR: Record<string, string> = {
  pdf: '#3e6fb0',
  png: '#b0567f',
  jpg: '#b0567f',
  jpeg: '#b0567f',
  webp: '#b0567f',
  gif: '#b0567f',
  xlsx: '#2f7d6b',
  xls: '#2f7d6b',
  csv: '#2f7d6b',
  docx: '#4a5bb0',
  doc: '#4a5bb0',
  pptx: '#b36a2e',
  ppt: '#b36a2e',
  mp4: '#a06cd5',
  mov: '#a06cd5',
  avi: '#a06cd5',
  txt: '#7a8496',
  md: '#7a8496',
  zip: '#5d7aa0',
  rar: '#5d7aa0',
}

const ext = computed(() => (props.fileType || 'FILE').replace(/^\./, '').toLowerCase())
const bg = computed(() => EXT_COLOR[ext.value] ?? '#6b7280')
const label = computed(() => ext.value.slice(0, 4).toUpperCase())
</script>

<template>
  <span class="file-tile" :class="`is-${size}`" :style="{ background: bg }">{{ label }}</span>
</template>

<style scoped>
.file-tile {
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #fff;
  font-family: var(--font-mono);
  font-weight: 700;
  letter-spacing: 0.02em;
  user-select: none;
}
.is-sm {
  width: 28px;
  height: 28px;
  font-size: 8.5px;
}
.is-md {
  width: 40px;
  height: 40px;
  font-size: 11px;
}
.is-lg {
  width: 54px;
  height: 54px;
  font-size: 14px;
  box-shadow: inset 0 0 0 1.5px rgb(255 255 255 / 0.25);
}
</style>
