import { defineStore } from 'pinia'
let seq=1
export interface ToastItem { id:number; text:string; kind:string; actionLabel?: string; action?: () => void }
export const useToast = defineStore('toast', {
  state: () => ({ items: [] as ToastItem[] }),
  actions: {
    show(text:string, kind='info'){ const id=seq++; this.items.push({id,text,kind}); setTimeout(()=>{ this.items=this.items.filter(i=>i.id!==id) },3600) },
    // 修订五 B：带操作按钮的提示条（如自动开始后的 [撤销]，5 秒内可点）
    showWithAction(text:string, actionLabel:string, action:() => void, ms=5000){ const id=seq++; this.items.push({id,text,kind:'info',actionLabel,action}); setTimeout(()=>{ this.items=this.items.filter(i=>i.id!==id) },ms) },
    runAction(id:number){ const it = this.items.find(i=>i.id===id); this.items = this.items.filter(i=>i.id!==id); it?.action?.() },
    // 修订二：页面跳转后清空全部提示，避免上一页的提示残留到新页面（rkaqv6sp 第 3 处）。
    clear(){ this.items = [] }
  }
})
