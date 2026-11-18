# 前端（Vue 3 + TypeScript + Vite）

## 两种运行模式（VITE_API_MODE）
- `real`（默认，交付给 jar 的构建）：统一 http 客户端以 `fetch` 请求真实后端 `baseURL=/api/v1`。
  - `npm run build`
- `mock`（演示环境演示）：请求由 `../mock-backend/` 的浏览器内内存实现处理，不发网络请求。
  - `npm run build:mock` 或 `VITE_API_MODE=mock npm run dev`

## 开发
`npm install && npm run dev`（real 模式下 Vite 将 /api 代理到 localhost:8080）

## 说明
- 页面不引用任何外网地址：字体（Inter 可变字体）、图标（lucide-vue-next）均本地打包。
- `/showcase` 为组件展示页；演示模式下 meta.demoMode=true，顶栏显示「演示环境 · 数据为模拟」。
- 演示账号（仅 mock 种子）：leader / po / member 等；口令由环境变量 DEMO_PASSWORD 在运行/部署时提供，未设置时种子使用占位符 CHANGE_ME_DEMO_PASSWORD（仅占位）。设置方法见根 README。
