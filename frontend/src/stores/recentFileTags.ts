import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { desktopBridge } from '@/api/desktop'
import { getFileDetail } from '@/api/files'
import { normalizeRecentTags, rememberFileTags } from '@/utils/recentTags'

/** A successful file open promotes that file's confirmed tags as one group. */
export const useRecentFileTagsStore = defineStore('recent-file-tags', () => {
  const auth = useAuthStore(), names = ref<string[]>([])
  let epoch = 0, ready = Promise.resolve(), updates = Promise.resolve()
  watch(() => [auth.user?.id, auth.isPersonal, auth.generation] as const, ([owner, personal]) => {
    const current = ++epoch
    names.value = []
    ready = (async () => {
      if (owner == null) return
      try {
        const saved = personal && desktopBridge?.getRecentTags
          ? await desktopBridge.getRecentTags()
          : JSON.parse(localStorage.getItem(`coffer:recent-file-tags:${owner}`) || '[]')
        if (current === epoch) names.value = normalizeRecentTags(saved)
      } catch { /* Preferences must not prevent opening files. */ }
    })()
  }, { immediate: true, flush: 'sync' })

  function recordOpened(id: number, revision?: number) {
    const current = epoch, owner = auth.user?.id, personal = auth.isPersonal, loaded = ready
    if (owner == null) return Promise.resolve()
    // Serialize opens, including metadata reads and writes, to preserve their order.
    updates = updates.then(async () => {
      await loaded
      if (current !== epoch) return
      const detail = (await getFileDetail(id, revision)).data.data
      if (current !== epoch) return
      const tags = detail.confirmedTags.map(tag => tag.tagName)
      if (!tags.length) return
      names.value = rememberFileTags(names.value, tags)
      if (personal && desktopBridge?.saveRecentTags) await desktopBridge.saveRecentTags(names.value.slice())
      else localStorage.setItem(`coffer:recent-file-tags:${owner}`, JSON.stringify(names.value))
    }).catch(() => { /* An optional history failure must not turn an opened file into an error. */ })
    return updates
  }
  return { names, recordOpened }
})
