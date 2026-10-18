const ONLINE_TYPES = new Set(['pdf', 'txt', 'png', 'jpg', 'jpeg', 'gif', 'bmp', 'webp'])

export function supportsOnlineReading(type?: string | null): boolean {
  return ONLINE_TYPES.has(type?.toLowerCase() ?? '')
}
