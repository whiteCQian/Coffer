<script setup lang="ts">
import AccountPanel from '@/components/AccountPanel.vue'
import RuntimeAdminPanel from '@/components/RuntimeAdminPanel.vue'
import DesktopBackupPanel from '@/components/DesktopBackupPanel.vue'
import AdminEmbeddingPanel from '@/components/AdminEmbeddingPanel.vue'
</script>
<template>
  <div class="admin-page">
    <h1>管理控制台</h1>
    <p>管理账号、统一向量服务与运行状态。用户文件、对话及 Chat / Vision 凭据仅向各自账号开放。</p>
    <AdminEmbeddingPanel />
    <AccountPanel />
    <DesktopBackupPanel />
    <RuntimeAdminPanel />
  </div>
</template>
<style scoped>
.admin-page { height: 100%; overflow: auto; padding: 20px; } .counts { display: flex; gap: 32px; margin: 24px 0; padding: 24px; } .counts div { display: grid; gap: 10px; } strong { font-size: 26px; }
</style>
