import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
const apiMode = process.env.VITE_API_MODE === 'mock' ? 'mock' : 'real'
export default defineConfig({
  plugins: [vue()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)), '@mock': fileURLToPath(new URL('../mock-backend/src', import.meta.url)), '@mock-adapter': fileURLToPath(new URL(apiMode === 'mock' ? './src/api/mock-adapter.ts' : './src/api/null-adapter.ts', import.meta.url)) } },
  server: { fs: { allow: [fileURLToPath(new URL('..', import.meta.url))] }, proxy: { '/api': 'http://localhost:8080' } },
  build: { chunkSizeWarningLimit: 1200 }
})
