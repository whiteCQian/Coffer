import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue'
import { desktopBridge } from '@/api/desktop'
export function usePaneWidth(key:string,fallback:number,min:number,max:Ref<number>){
  const preferred=ref(fallback)
  let edited=false
  try{const saved=Number(localStorage.getItem(key));if(Number.isFinite(saved) && saved>=min && saved<=1200)preferred.value=saved}catch{}
  const width=computed(()=>Math.max(min,Math.min(preferred.value,Math.max(min,max.value))))
  onMounted(async()=>{
    try{
      const saved=(await desktopBridge?.getPaneWidths?.())?.[key]
      if(!edited && typeof saved==='number' && Number.isFinite(saved) && saved>=min && saved<=1200)preferred.value=saved
    }catch{}
  })
  const resize=(value:number)=>{edited=true;preferred.value=Math.max(min,Math.min(value,Math.max(min,max.value)))}
  const commit=()=>{try{localStorage.setItem(key,String(preferred.value))}catch{};void desktopBridge?.savePaneWidth?.(key,preferred.value).catch(()=>{})}
  const reset=()=>{edited=true;preferred.value=fallback;commit()}
  return {width,resize,commit,reset}
}
export function useWindowWidth(){
  const width=ref(window.innerWidth),update=()=>{width.value=window.innerWidth}
  onMounted(()=>window.addEventListener('resize',update));onUnmounted(()=>window.removeEventListener('resize',update))
  return width
}
