/** Recent uploads show analysis and tag progress, independently of formal archiving. */
export function recentUploadStateLabel(file: {
  status?: string | null
  archived?: boolean
  taskState?: string | null
  tagStatus?: string | null
  tags?: { confirmationStatus?: string | null }[] | null
}) {
  if (file.status === 'PENDING') return '排队中'
  if (file.status === 'PROCESSING') return '正在解析'
  if (file.status === 'FAILED') return '解析未完成'
  if (file.archived) return '已归档'
  if (file.status === 'COMPLETED') {
    const pending = file.tags != null
      ? file.tags.some(tag => tag.confirmationStatus === 'PENDING_CONFIRMATION')
      : file.tagStatus === 'PENDING'
    return pending ? '待确认标签' : '已解析'
  }
  return '已保存'
}

export function recentUploadStateTone(file: Parameters<typeof recentUploadStateLabel>[0]) {
  const label = recentUploadStateLabel(file)
  return label === '已归档' ? 'archived' : label === '已解析' ? 'parsed'
    : label === '待确认标签' ? 'awaiting' : label === '正在解析' ? 'processing'
      : label === '解析未完成' ? 'failed' : 'unparsed'
}
