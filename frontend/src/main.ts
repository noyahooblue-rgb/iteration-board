import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/inter'
import './design/base.css'
import App from './App.vue'
import router from './router'
import { initAppearance } from './utils/appearance'
initAppearance() // 启动即应用外观（本地缓存首屏生效；登录后 auth.loadMe 再以服务端主题偏好为准）
document.addEventListener('touchstart', () => {}, { passive: true }) // iOS :active 生效（手册 ）
const pinia = createPinia()
createApp(App).use(pinia).use(router).mount('#app')
// 外观（生效主题）变化后，团队主题色按深/浅映射重应用 --theme
import { useTeam } from './stores/team'
document.addEventListener('ib-appearance', () => { try { useTeam(pinia).applyTheme() } catch {} })
