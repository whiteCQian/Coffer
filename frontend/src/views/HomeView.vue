<script setup lang="ts">
// 首页工作台：上传区 + AI 分类 + 最近上传 + AI 解析进度 + AI 管家
import AiProgress from '@/layout/AiProgress.vue'
import ChatDock from '@/layout/ChatDock.vue'
import RecentUploads from '@/layout/RecentUploads.vue'
import SuggestTags from '@/layout/SuggestTags.vue'
import UploadZone from '@/layout/UploadZone.vue'
</script>

<template>
  <div class="home">
    <!-- 左：主内容区（上传区全宽 + 左下分类 + 右下最近上传） -->
    <div class="home-main">
      <UploadZone />
      <div class="home-grid">
        <SuggestTags />
        <RecentUploads />
      </div>
    </div>

    <!-- 右：辅助面板（AI 解析进度 + AI 管家对话） -->
    <div class="home-side">
      <AiProgress />
      <ChatDock />
    </div>
  </div>
</template>

<style scoped>
.home {
  height: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.72fr);
  gap: var(--gap);
  min-width: 0;
  min-height: 0;
}

/* 主区：顶部上传区占 35% 高，下方左右两列（标签推荐 35% 宽 / 最近上传余下） */
.home-main {
  display: grid;
  grid-template-rows: minmax(0, 35%) minmax(0, 1fr);
  gap: var(--gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.home-grid {
  display: grid;
  grid-template-columns: minmax(0, 35%) minmax(0, 1fr);
  gap: var(--gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}

/* 右区：进度面板自适应但封顶 45%（内容多时内部滚动），对话区始终占剩余 */
.home-side {
  display: grid;
  grid-template-rows: minmax(0, 45%) minmax(0, 1fr);
  gap: var(--gap);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
</style>
