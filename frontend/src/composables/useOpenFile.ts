import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import { fileDownloadUrl } from '@/api/files'
import { useAuthStore } from '@/stores/auth'
import { useRecentFileTagsStore } from '@/stores/recentFileTags'

const opening = ref(new Set<string>())
const cooldown = new Map<string, number>()
const INLINE_TYPES = new Set(['pdf', 'txt', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp'])
export function useOpenFile() {
  const auth = useAuthStore()
  const recent = useRecentFileTagsStore()
  function key(id: number) { return `${auth.generation}:${id}` }
  function isOpening(id: number) { return opening.value.has(key(id)) }
  async function openFile(id: number, revision?: number, type?: string | null) {
    const identity = key(id)
    if (!Number.isSafeInteger(id) || id < 1 || isOpening(id) || Date.now() - (cooldown.get(identity) ?? 0) < 800) return
    if (!desktopBridge) {
      if (cooldown.size > 256) cooldown.clear()
      cooldown.set(identity, Date.now())
      const inline = INLINE_TYPES.has(type?.toLowerCase() ?? '')
      const url = inline ? `/api/files/${id}/content${revision == null ? '' : '?revision=' + revision}` : fileDownloadUrl(id, revision)
      const link = document.createElement('a'); link.href = url
      if (inline) { link.target = '_blank'; link.rel = 'noopener noreferrer' }
      else link.download = ''
      link.click()
      void recent.recordOpened(id, revision)
      return
    }
    if (!desktopBridge.openFile) { ElMessage.error('当前桌面程序尚未支持直接打开，请更新程序后重试'); return }
    opening.value = new Set(opening.value).add(identity)
    try {
      const result = await desktopBridge.openFile(id, revision)
      if (key(id) !== identity) return
      if (result.status !== 'OPENED') ElMessage.error(result.message || '文件打开失败，请检查默认应用后重试')
      else void recent.recordOpened(id, revision)
    } catch (error) {
      if (key(id) === identity) ElMessage.error(String((error as Error)?.message || '文件打开失败').replace(/^Error invoking remote method .*?: Error: /, ''))
    } finally {
      const next = new Set(opening.value); next.delete(identity); opening.value = next
      if (cooldown.size > 256) cooldown.clear()
      cooldown.set(identity, Date.now())
    }
  }
  return { isOpening, openFile }
}
