<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
const props=defineProps<{label:string;size:number;min:number;max:number;reverse?:boolean}>()
const emit=defineEmits<{resize:[value:number];commit:[];reset:[]}>()
const dragging=ref(false)
let startX=0,startSize=0,pointerId:number|undefined,handle:HTMLElement|undefined,previousCursor='',previousSelection=''
function start(event:PointerEvent){
  if(event.button!==0)return
  event.preventDefault();startX=event.clientX;startSize=props.size;pointerId=event.pointerId;handle=event.currentTarget as HTMLElement
  handle.setPointerCapture(pointerId);dragging.value=true
  previousCursor=document.body.style.cursor;previousSelection=document.body.style.userSelect
  document.body.style.cursor='col-resize';document.body.style.userSelect='none'
}
function move(event:PointerEvent){if(dragging.value && event.pointerId===pointerId)emit('resize',startSize+(event.clientX-startX)*(props.reverse?-1:1))}
function finish(cancel=false){
  if(!dragging.value)return
  dragging.value=false
  if(cancel)emit('resize',startSize);else emit('commit')
  document.body.style.cursor=previousCursor;document.body.style.userSelect=previousSelection
  if(pointerId!==undefined && handle?.hasPointerCapture(pointerId))handle.releasePointerCapture(pointerId)
  pointerId=undefined
}
function key(event:KeyboardEvent){
  let value=props.size
  const step=event.shiftKey?40:10,direction=props.reverse?-1:1
  if(event.key==='ArrowLeft')value-=step*direction
  else if(event.key==='ArrowRight')value+=step*direction
  else if(event.key==='Home')value=props.min
  else if(event.key==='End')value=props.max
  else if(event.key==='Enter'){event.preventDefault();emit('reset');return}
  else return
  event.preventDefault();emit('resize',value);emit('commit')
}
onUnmounted(()=>finish(true))
</script>
<template><div class="pane-resize-handle" :class="{'is-dragging':dragging}" role="separator" aria-orientation="vertical" :aria-label="label" :aria-valuenow="Math.round(size)" :aria-valuemin="min" :aria-valuemax="Math.max(min,max)" tabindex="0" title="拖动调整宽度 · 双击恢复默认" @pointerdown="start" @pointermove="move" @pointerup="finish()" @pointercancel="finish(true)" @lostpointercapture="finish()" @keydown="key" @dblclick="emit('reset')"><span aria-hidden="true"></span></div></template>
<style scoped>
.pane-resize-handle{position:relative;flex:0 0 8px;width:8px;min-height:0;cursor:col-resize;touch-action:none;background:var(--panel,#fff);z-index:3;outline-offset:-2px}.pane-resize-handle::before{content:'';position:absolute;inset:0 auto 0 3px;width:1px;background:var(--line,#dce4df)}.pane-resize-handle span{position:absolute;top:calc(50% - 16px);left:2px;width:3px;height:32px;border-radius:2px;background:var(--line-strong,#b8c9cc)}.pane-resize-handle:hover span,.pane-resize-handle:focus-visible span,.pane-resize-handle.is-dragging span{background:var(--accent,#347f90)}.pane-resize-handle.is-dragging,.pane-resize-handle:hover{background:rgb(var(--accent-rgb,52 127 144) / .06)}
</style>
