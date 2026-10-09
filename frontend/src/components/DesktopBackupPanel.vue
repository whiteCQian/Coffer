<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { desktopBridge } from '@/api/desktop'
import type { DesktopBackupResult } from '@/api/desktop'
const directory=ref(''),password=ref(''),repeat=ref(''),local=ref(false),busy=ref(false),result=ref<DesktopBackupResult|null>(null)
function error(value: unknown){ElMessage.error(String((value as Error)?.message || '整套备份未完成').replace(/^Error invoking remote method .*?: Error: /,''))}
async function choose(){try{const selected=await desktopBridge?.chooseBackupDestination();if(selected)directory.value=selected.directory}catch(failed){error(failed)}}
async function backup(){
  if(!desktopBridge || busy.value)return
  if(password.value.length<12 || password.value!==repeat.value){ElMessage.warning('请填写至少 12 个字符且两次一致的备份口令');return}
  busy.value=true;result.value=null
  try{result.value=await desktopBridge.createBackup({password:password.value,localSnapshot:local.value})}
  catch(failed){error(failed)}finally{password.value='';repeat.value='';busy.value=false}
}
</script>
<template><section v-if="desktopBridge" class="desktop-backup">
  <h2>整套加密备份</h2><p>包含 H2 一致性快照、所有用户正文与工作副本、配置、台账及原密钥。操作期间停止后台，请先关闭外部编辑应用。</p>
  <button :disabled="busy" @click="choose">选择备份介质目录</button><p>{{ directory || '尚未选择' }}</p>
  <label>备份口令<input v-model="password" type="password" autocomplete="new-password" minlength="12" :disabled="busy"></label>
  <label>重复口令<input v-model="repeat" type="password" autocomplete="new-password" minlength="12" :disabled="busy"></label>
  <label class="snapshot-option"><input v-model="local" type="checkbox" :disabled="busy">仅生成本机加密快照（不算独立介质备份）</label>
  <p>标准备份需不同物理设备或独立网络存储。不同盘符若仍是同一物理硬盘会被拒绝。口令不随包保存，恢复时需自行提供。</p>
  <button :disabled="busy || !directory" @click="backup">{{ busy ? '正在校验、加密和读回验证…' : '生成并验证整套备份' }}</button>
  <div v-if="result" role="status"><strong>{{ result.independent ? '独立介质加密备份已验证' : '本机加密快照已验证，独立介质备份待完成' }}</strong><p>{{ result.packagePath }}</p><p>账号 {{ result.users }} · 正式文件 {{ result.files }} · 台账/任务 {{ result.ledgerRows }}</p><p class="digest">SHA-256：{{ result.packageSha256 }}</p></div>
</section></template>
<style scoped>.desktop-backup{padding:20px;border:1px solid var(--line,#ccc);border-radius:12px;margin:20px 0;font-size:13px}.desktop-backup h2{font-size:18px}.desktop-backup label{display:flex;gap:10px;align-items:center;margin:12px 0}.desktop-backup input[type=password]{padding:8px;border:1px solid #bbb;border-radius:6px}.desktop-backup button{padding:9px 14px;border:0;border-radius:8px;background:var(--accent,#3f8290);color:white;cursor:pointer}.desktop-backup p{line-height:1.6;overflow-wrap:anywhere}.desktop-backup button:disabled{opacity:.5}</style>
