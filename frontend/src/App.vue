<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import RuntimeStatusBanner from '@/components/RuntimeStatusBanner.vue'
const auth = useAuthStore()
</script>

<template>
  <router-view v-if="auth.user || $route.meta.public" :key="auth.generation" />
  <RuntimeStatusBanner v-if="auth.user && !auth.isAdmin" :key="auth.generation" />
</template>
