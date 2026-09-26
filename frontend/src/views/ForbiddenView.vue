<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
</script>
<template>
  <section class="forbidden pg-panel">
    <h1>无权访问此页面</h1>
    <p>当前账号无法访问此功能。私有工作区只向对应普通用户开放。</p>
    <router-link :to="auth.isAdmin ? '/admin' : '/'">返回{{ auth.isAdmin ? '管理控制台' : '我的工作区' }}</router-link>
  </section>
</template>
<style scoped>.forbidden { margin: 48px; padding: 32px; }</style>
