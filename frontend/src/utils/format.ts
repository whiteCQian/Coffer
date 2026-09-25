/** 展示与换算工具 */

/** 字节数 → 可读大小 */
export function formatBytes(bytes: number): string {
  if (bytes == null || Number.isNaN(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = bytes
  let i = -1
  do {
    v /= 1024
    i++
  } while (v >= 1024 && i < units.length - 1)
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)} ${units[i]}`
}

/** 'YYYY-MM-DD HH:mm' → 'MM-DD HH:mm'（跨年时补年份） */
export function shortTime(uploadTime: string): string {
  if (!uploadTime) return ''
  const m = uploadTime.match(/^(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2})/)
  if (!m) return uploadTime
  const [, year, mon, day, hm] = m
  return year === '2026' ? `${mon}-${day} ${hm}` : `${year.slice(2)}-${mon}-${day} ${hm}`
}

/** 'YYYY-MM-DD HH:mm' → 时间戳（用于排序） */
export function timeToTs(uploadTime: string): number {
  const d = new Date(uploadTime.replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? 0 : d.getTime()
}

/** 后端 ISO-8601 时间串（如 2026-09-02T14:02:00）→ 'YYYY-MM-DD HH:mm'；
 *  兼容骨架期 mock 的空格格式（原样截断）。供接入真实接口后统一展示。 */
export function toDisplayTime(t: string | null | undefined): string {
  if (!t) return ''
  return t.replace('T', ' ').slice(0, 16)
}

/** 截断长文本 */
export function clamp(text: string, max = 100): string {
  if (!text) return text
  return text.length > max ? `${text.slice(0, max)}…` : text
}
