<script setup lang="ts">
// 应用外壳：左侧 64px 图标导航 + 右侧页面视口（路由出口）
import IconRail from './IconRail.vue'
</script>

<template>
  <div class="app-shell">
    <IconRail />
    <main class="page-viewport">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  height: 100vh;
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: var(--gap);
  padding: var(--gap);
  background: var(--bg);
  overflow: hidden;
}

/* 页面视口：交给各页面自行组织布局与滚动 */
.page-viewport {
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
</style>
