<script setup lang="ts">
import AccountPanel from '@/components/AccountPanel.vue'
import RuntimeAdminPanel from '@/components/RuntimeAdminPanel.vue'
import DesktopBackupPanel from '@/components/DesktopBackupPanel.vue'
</script>
<template>
  <div class="admin-page">
    <h1>管理控制台</h1>
    <p>管理账号与查看运行数量。用户文件、对话和模型凭据仅向各自账号开放。</p>
    <AccountPanel />
    <DesktopBackupPanel />
    <RuntimeAdminPanel />
  </div>
</template>
<style scoped>
.admin-page { height: 100%; overflow: auto; padding: 20px; } .counts { display: flex; gap: 32px; margin: 24px 0; padding: 24px; } .counts div { display: grid; gap: 10px; } strong { font-size: 26px; }
</style>
