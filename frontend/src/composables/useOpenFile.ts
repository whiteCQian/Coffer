import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import { fileDownloadUrl } from '@/api/files'
import { useAuthStore } from '@/stores/auth'
import { useRecentFileTagsStore } from '@/stores/recentFileTags'
import { supportsOnlineReading } from '@/utils/onlineReading'

const opening = ref(new Set<string>())
const cooldown = new Map<string, number>()
export function useOpenFile() {
  const auth = useAuthStore()
  const recent = useRecentFileTagsStore()
  function key(id: number) { return `${auth.generation}:${id}` }
  function isOpening(id: number) { return opening.value.has(key(id)) }
  function canOpenFile(type?: string | null) { return Boolean(desktopBridge) || supportsOnlineReading(type) }
  async function openFile(id: number, revision?: number, type?: string | null) {
    const identity = key(id)
    if (!Number.isSafeInteger(id) || id < 1 || isOpening(id) || Date.now() - (cooldown.get(identity) ?? 0) < 800) return
    if (!canOpenFile(type)) return
    if (desktopBridge && !desktopBridge.openFile) { ElMessage.error('当前桌面程序尚未支持直接打开，请更新程序后重试'); return }
    opening.value = new Set(opening.value).add(identity)
    let preview: Window | null = null
    try {
      if (!desktopBridge) {
        const inline = supportsOnlineReading(type)
        const url = inline ? `/api/files/${id}/content${revision == null ? '' : '?revision=' + revision}` : fileDownloadUrl(id, revision)
        // Reserve the tab during the click so slow requests do not trigger popup blocking.
        if (inline) {
          preview = window.open('about:blank', '_blank')
          if (!preview) { ElMessage.error('预览窗口未能打开，请允许本站弹出窗口后重试'); return }
          preview.opener = null
        }
        const response = await fetch(url, { method: 'HEAD', credentials: 'same-origin', signal: AbortSignal.timeout(15000) })
        if (key(id) !== identity) { preview?.close(); return }
        if (!response.ok) throw new Error(response.status === 404 ? '文件已不存在，请刷新文件库后重试' : '文件暂时无法打开，请刷新后重试')
        if (preview) {
          if (preview.closed) return
          preview.location.replace(url)
        } else {
          const link = document.createElement('a'); link.href = url; link.download = ''; link.click()
        }
        void recent.recordOpened(id, revision)
        return
      }
      const result = await desktopBridge.openFile!(id, revision)
      if (key(id) !== identity) return
      if (result.status !== 'OPENED') ElMessage.error(result.message || '文件打开失败，请检查默认应用后重试')
      else void recent.recordOpened(id, revision)
    } catch (error) {
      preview?.close()
      if (key(id) === identity) ElMessage.error(String((error as Error)?.message || '文件打开失败').replace(/^Error invoking remote method .*?: Error: /, ''))
    } finally {
      const next = new Set(opening.value); next.delete(identity); opening.value = next
      if (cooldown.size > 256) cooldown.clear()
      cooldown.set(identity, Date.now())
    }
  }
  return { isOpening, openFile, canOpenFile }
}
