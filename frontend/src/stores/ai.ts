import { defineStore } from 'pinia'
import { api } from '../api/http'
import { DEFAULT_TEAM_AI_SETTINGS, type AiStatus } from '../api/types'
// 全局 AI 状态：进入小组后先调 ai/status；不可用时各 AI 入口显示「AI 暂不可用」并给出手工路径，其余功能不受影响。
// 契约 0.5.0：可用性以 status.available 为准（平台开关+配置合并）；features 键为连字符名，前端功能名（camel）在此映射。
const FEATURE_KEYS: Record<string, string> = { pasteImport: 'paste-import', quickUpdate: 'quick-update', splitStories: 'split-stories', summarize: 'summarize', riskSummary: 'risk-summary', qa: 'qa' }
export const useAi = defineStore('ai', {
  state: () => ({ status: null as AiStatus|null, teamId: 0, loaded: false }),
  getters: {
    available: (s) => !!s.status && s.status.available,
    unavailableReason: (s) => !s.status ? '' : !s.status.platformEnabled ? '平台已关闭 AI' : !s.status.available ? 'AI 服务暂时不可用' : '',
    featureOn: (s) => (f: string) => !!s.status && s.status.available && s.status.features[FEATURE_KEYS[f] ?? f] !== false,
  },
  actions: {
    async load(teamId: number) { if (!teamId) return; if (this.teamId === teamId && this.loaded) return
      this.teamId = teamId
      try { this.status = await api.get<AiStatus>(`/teams/${teamId}/ai/status`) } catch { this.status = { platformEnabled: false, mode: 'mock', available: false, visionAvailable: false, textModel: null, visionModel: null, features: {}, teamSettings: { ...DEFAULT_TEAM_AI_SETTINGS } } }
      this.loaded = true },
    async refresh(teamId: number) { this.loaded = false; await this.load(teamId) },
  }
})
