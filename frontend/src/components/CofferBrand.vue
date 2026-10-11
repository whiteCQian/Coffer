<script setup lang="ts">
import CofferLogo from './CofferLogo.vue'
defineProps<{subtitle?:string}>()
</script>
<template><span class="coffer-brand"><CofferLogo/><span class="brand-copy"><strong>Coffer</strong><small v-if="subtitle">{{ subtitle }}</small></span></span></template>
<style scoped>
.coffer-brand{display:inline-flex;align-items:center;gap:11px;min-width:0}.brand-copy{min-width:0}.brand-copy strong{display:block;color:#413a33;font-family:'Arial Rounded MT Bold','Trebuchet MS','Segoe UI',sans-serif;font-size:var(--coffer-wordmark-size,22px);font-weight:750;letter-spacing:-.8px;line-height:1.25}.brand-copy small{display:block;color:var(--text-2,#687975);font-size:11px;line-height:1.5;margin-top:3px}
:global(html[data-theme='dark'] .coffer-brand .brand-copy strong){color:var(--text-1,#f2ede6)}
</style>
