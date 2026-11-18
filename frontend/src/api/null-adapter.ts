// real 构建用：不包含任何 mock-backend 代码，保证模拟后端永不进 jar。
export async function handleRequest(_method: string, _path: string, _body?: any, _headers?: Record<string,string>): Promise<{ status: number; data: any }> {
  throw new Error('mock backend is disabled in real build')
}

export const DEMO_ACCOUNTS: { label:string; login:string; name:string; color:string }[] = []
export const DEMO_SUBTITLE = ''
export const DEMO_FILL_HINT = ''
export const DEMO_REG_CLOSED_TEXT = ''
export const DEMO_PASSWORD_FILL = ''
// real 构建不用适配器的事件订阅（http.ts 走原生 EventSource），此导出仅满足别名接口。
export function subscribeEvents(_cb: (n: any) => void): () => void { return () => {} }
