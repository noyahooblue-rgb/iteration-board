import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/inter'
import './design/base.css'
import App from './App.vue'
import router from './router'
import { initAppearance } from './utils/appearance'
initAppearance() // 启动即应用外观（主题/玻璃/动效偏好，存 localStorage）
document.addEventListener('touchstart', () => {}, { passive: true }) // iOS :active 生效（手册 ）
createApp(App).use(createPinia()).use(router).mount('#app')
