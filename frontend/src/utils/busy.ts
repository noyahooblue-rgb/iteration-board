import { ref } from 'vue'
// Q13：所有会写入服务端的动作在请求期间禁用按钮——统一 busy 包装（按 key 区分按钮）。
export function useBusy() {
  const keys = ref(new Set<string>())
  const is = (k: string) => keys.value.has(k)
  async function wrap<T>(k: string, fn: () => Promise<T>): Promise<T | undefined> {
    if (keys.value.has(k)) return undefined
    keys.value = new Set(keys.value).add(k)
    try { return await fn() } finally { const s = new Set(keys.value); s.delete(k); keys.value = s }
  }
  return { keys, is, wrap }
}
