<script setup lang="ts">
import { computed } from 'vue'
import { useArchiveFile } from '@/composables/useArchiveFile'
const props = defineProps<{ fileId: number; archived?: boolean; status?: string | null }>()
const emit = defineEmits<{ changed: [] }>()
const { archive, job } = useArchiveFile()
const phase = computed(() => job(props.fileId)?.phase)
const parsing = computed(() => props.status === 'PENDING' || props.status === 'PROCESSING')
const label = computed(() => props.archived ? '已归档' : phase.value === 'running' ? '正在归档…' : phase.value === 'waiting' ? '查看归档进度' : phase.value === 'failed' ? '重试归档' : '归档')
</script>
<template>
  <button class="archive-file-button" :disabled="archived || parsing || phase === 'running'" :title="parsing ? '解析完成后即可归档' : '按当前名称、摘要和标签归档'" @click.stop="archive(fileId, () => emit('changed'))">{{ label }}</button>
</template>
