<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'
import RuntimeStatusBanner from '@/components/RuntimeStatusBanner.vue'
import { watch } from 'vue'
const auth = useAuthStore()
watch(() => auth.isPersonal, value => { document.documentElement.dataset.desktop = String(value) }, { immediate: true })
</script>

<template>
  <router-view v-if="auth.user || $route.meta.public" :key="auth.generation" />
  <RuntimeStatusBanner v-if="auth.user && !auth.isAdmin" :key="auth.generation" />
</template>
