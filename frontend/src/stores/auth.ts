import { defineStore } from 'pinia'
import { api } from '../api/http'
import type { User } from '../api/types'
import { syncAppearanceFromServer } from '../utils/appearance'
export const useAuth = defineStore('auth', {
  state: () => ({ user: null as User|null, meta: { registrationOpen: true, demoMode: false } }),
  actions: {
    async loadMeta(){ try{ this.meta = await api.get('/meta') }catch{} },
    async loadMe(){ try{ this.user = await api.get('/auth/me'); if (this.user) await syncAppearanceFromServer(); return this.user }catch{ this.user=null; return null } },
    async logout(){ try{ await api.post('/auth/logout') }catch{} this.user=null },
  }
})
