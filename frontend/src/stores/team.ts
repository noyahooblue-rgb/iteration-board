import { defineStore } from 'pinia'
import { api } from '../api/http'
import type { Team, Member, Iteration } from '../api/types'
const THEMES: Record<string,string> = { blue:'#007AFF', indigo:'#5856D6', violet:'#AF52DE', pink:'#FF2D55', orange:'#FF9500', green:'#34C759', teal:'#30B0C7' }
// 深色两主题下团队主题色用定稿提亮版（定稿 §2 原则 3：彩色在深色下用提亮系统色；blue = --primary #409CFF）
const THEMES_DARK: Record<string,string> = { blue:'#409CFF', indigo:'#9E9BFF', violet:'#BF5AF2', pink:'#FF375F', orange:'#FF9F0A', green:'#30D158', teal:'#40CBE0' }
export const useTeam = defineStore('team', {
  state: () => ({ teams: [] as Team[], currentId: 0, members: [] as Member[], iteration: null as Iteration|null }),
  getters: { current: (s)=> s.teams.find(t=>t.id===s.currentId) ?? null, memberName: (s)=> (id:number|null|undefined)=> s.members.find(m=>m.userId===id)?.displayName ?? '未指派', member: (s)=> (id:number|null|undefined)=> s.members.find(m=>m.userId===id) },
  actions: {
    applyTheme(){ const c = this.current?.themeColor ?? 'blue'; const dark = ['dark','graphite'].includes(document.documentElement.dataset.theme ?? ''); const map = dark ? THEMES_DARK : THEMES; document.documentElement.style.setProperty('--theme', map[c] ?? map.blue) },
    async loadTeams(){ this.teams = await api.get('/teams/mine'); const saved = Number(localStorage.getItem('current-team') ?? 0); this.currentId = this.teams.some(t=>t.id===saved) ? saved : (this.teams[0]?.id ?? 0); this.applyTheme(); if (this.currentId) await this.loadTeamData() },
    async switchTeam(id:number){ this.currentId=id; localStorage.setItem('current-team', String(id)); this.applyTheme(); await this.loadTeamData() },
    async loadTeamData(){ if(!this.currentId) return; this.members = await api.get(`/teams/${this.currentId}/members`).catch(()=>[]); this.iteration = await api.get(`/teams/${this.currentId}/iterations/current`).catch(()=>null) },
  }
})
