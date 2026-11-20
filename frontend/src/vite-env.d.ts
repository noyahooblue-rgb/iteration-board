/// <reference types="vite/client" />
declare module '*.vue' { import type { DefineComponent } from 'vue'; const c: DefineComponent<{}, {}, any>; export default c }
// 构建期常量（vite.config define 注入）：正式构建为 false，showcase 路由/导航被静态消除。
declare const __IB_SHOWCASE__: boolean
