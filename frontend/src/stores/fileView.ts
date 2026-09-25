import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 首页视图状态：当前选中的 AI 标签（null = 「最近」视图，即最近上传 8 个文件）。
 * - 「猜你需要的标签」卡片第 1 格「最近」（选中态 = 无标签）→ 右侧展示最近 8 个上传；
 *   点其余真实标签 → 按标签过滤「最近上传」区
 * - 固定分类已从 UI 下线（仅作后端内部归档目录），不再有分类维度
 */
export const useFileViewStore = defineStore('fileView', () => {
  /** 当前选中的标签名；null = 「最近」视图（不按标签过滤，展示最近上传） */
  const activeTag = ref<string | null>(null)

  /** 点标签：选中或再次点击取消（取消后回到「最近」视图） */
  function toggleTag(name: string) {
    activeTag.value = activeTag.value === name ? null : name
  }

  /** 清除标签筛选（回到「最近」视图 = 最近 8 个上传文件） */
  function clearTag() {
    activeTag.value = null
  }

  return { activeTag, toggleTag, clearTag }
})
