import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
const apiMode = process.env.VITE_API_MODE === 'mock' ? 'mock' : 'real'
// 组件展示（Showcase）构建期开关（1.0.4 C 双保险之一）：
// 仅 dev（mode=development）、演示构建（VITE_API_MODE=mock）或显式 VITE_SHOWCASE=1 时为 true；
// 默认真实构建为 false，__IB_SHOWCASE__ 被 define 替换为字面量 false，路由表与导航中的
// showcase 分支被 Rollup 静态消除，dist 里不留 ShowcaseView / 「组件展示」痕迹（CI 隔离检查 grep 断言）。
export default defineConfig(({ mode }) => {
  const showcase = mode === 'development' || apiMode === 'mock' || process.env.VITE_SHOWCASE === '1'
  return {
    plugins: [vue()],
    define: { __IB_SHOWCASE__: JSON.stringify(showcase) },
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)), '@mock': fileURLToPath(new URL('../mock-backend/src', import.meta.url)), '@mock-adapter': fileURLToPath(new URL(apiMode === 'mock' ? './src/api/mock-adapter.ts' : './src/api/null-adapter.ts', import.meta.url)) } },
    server: { fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] }, proxy: { '/api': 'http://localhost:8080' } },
    build: { chunkSizeWarningLimit: 1200 },
  }
})
