<template>
<ul class="rules" aria-label="密码规则">
  <li v-for="r in rules" :key="r.label" :class="{ ok: r.ok }"><span class="mark">{{ r.ok ? '✓' : '○' }}</span>{{ r.label }}</li>
</ul>
</template>
<script setup lang="ts">
// 修订六契约 5：密码规则清单，与后端 PasswordPolicy 同规则：
// 8–64 位 / 含字母 / 含数字 / 含特殊字符（非字母数字且非空白的可见字符）/ 不能包含登录名（大小写不敏感）。
import { computed } from 'vue'
const props = defineProps<{ password: string; loginName?: string | null }>()
const rules = computed(() => {
  const p = props.password ?? ''
  const login = (props.loginName ?? '').trim()
  return [
    { label: '8–64 位', ok: p.length >= 8 && p.length <= 64 },
    { label: '含字母', ok: /[A-Za-z]/.test(p) },
    { label: '含数字', ok: /\d/.test(p) },
    { label: '含特殊字符（如 !@# 等）', ok: /[^A-Za-z0-9\s]/.test(p) },
    { label: '不含登录名', ok: p.length > 0 && (!login || !p.toLowerCase().includes(login.toLowerCase())) },
  ]
})
</script>
<style scoped>
.rules{list-style:none;display:flex;flex-wrap:wrap;gap:4px 14px;margin-top:8px;font-size:12.5px;color:var(--text-3)}
.rules li{display:inline-flex;gap:5px;align-items:center}
.rules li.ok{color:var(--success-text)}
.rules .mark{font-weight:700}
</style>
