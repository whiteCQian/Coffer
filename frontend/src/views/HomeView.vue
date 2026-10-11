<script setup lang="ts">
import FilesView from './FilesView.vue'
import DesktopQueueView from './DesktopQueueView.vue'
import WebHomeView from './WebHomeView.vue'
import { useAuthStore } from '@/stores/auth'
import { useRoute } from 'vue-router'
const auth = useAuthStore(), route = useRoute()
</script>
<template>
  <DesktopQueueView v-if="auth.isPersonal && (route.query.view === 'inbox' || route.query.view === 'pending')" />
  <FilesView v-else-if="auth.isPersonal" />
  <WebHomeView v-else />
</template>
