import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/inter'
import './design/base.css'
import App from './App.vue'
import router from './router'
document.addEventListener('touchstart', () => {}, { passive: true }) // iOS :active 生效（手册 ）
createApp(App).use(createPinia()).use(router).mount('#app')
