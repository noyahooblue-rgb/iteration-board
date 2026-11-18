import { defineStore } from 'pinia'
export const useUi = defineStore('ui', { state: () => ({ createOpen: false, collapsed: false, searchOpen: false, createPreset: null as any }) })
