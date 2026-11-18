import { defineStore } from 'pinia'
let seq=1
export const useToast = defineStore('toast', {
  state: () => ({ items: [] as {id:number;text:string;kind:string}[] }),
  actions: {
    show(text:string, kind='info'){ const id=seq++; this.items.push({id,text,kind}); setTimeout(()=>{ this.items=this.items.filter(i=>i.id!==id) },3600) },
    // 修订二：页面跳转后清空全部提示，避免上一页的提示残留到新页面（rkaqv6sp 第 3 处）。
    clear(){ this.items = [] }
  }
})
