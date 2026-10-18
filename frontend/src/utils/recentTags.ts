export const RECENT_TAG_LIMIT = 8
export function normalizeRecentTags(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return [...new Set(value.filter((name): name is string => typeof name === 'string' && !!name.trim() && name.length <= 512))].slice(0, RECENT_TAG_LIMIT)
}
export function rememberTag(history: string[], name: string): string[] {
  return normalizeRecentTags([name, ...history.filter(item => item !== name)])
}
export function rememberFileTags(history: string[], tags: string[]): string[] {
  return normalizeRecentTags([...tags, ...history])
}
