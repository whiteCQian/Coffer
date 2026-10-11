import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listTagCandidates } from '@/api/fileTags'
import { getOverview } from '@/api/tasks'
import type { TagCandidate, TaskOverviewResponse } from '@/api/types'

/** Shared desktop navigation counts; refreshed after a file mutation and on a timer. */
export const useWorkspaceStore = defineStore('workspace', () => {
  const tags = ref<TagCandidate[]>([])
  const overview = ref<TaskOverviewResponse | null>(null)
  const unavailable = ref(false)
  let epoch = 0
  let refreshing: Promise<void> | undefined
  async function refresh() {
    if (refreshing) return refreshing
    const current = epoch
    refreshing = (async () => {
      const results = await Promise.allSettled([listTagCandidates(), getOverview()])
      if (current !== epoch) return
      const [tagResult, taskResult] = results
      if (tagResult.status === 'fulfilled') tags.value = (tagResult.value.data.data ?? []).slice(0, 12)
      if (taskResult.status === 'fulfilled') {
        overview.value = taskResult.value.data.data
        unavailable.value = false
      } else {
        overview.value = null
        unavailable.value = true
      }
    })().finally(() => { if (current === epoch) refreshing = undefined })
    return refreshing
  }
  function clear() {
    epoch++
    refreshing = undefined
    tags.value = []
    overview.value = null
    unavailable.value = false
  }
  return { tags, overview, unavailable, refresh, clear }
})
