<template>
<div class="wrap">
  <GlassPanel class="card">
    <Hourglass :size="36" class="ic"/>
    <h1>加入申请已提交</h1>
    <p class="small">你申请加入的小组需要组长审批。审批通过后，点击下方按钮刷新状态即可进入小组；被拒绝时申请会失效，可以重新输入邀请码申请。</p>
    <p v-if="teamName" class="small">申请小组：<b>{{teamName}}</b></p>
    <div class="row"><AppButton @click="refresh">刷新状态</AppButton><AppButton variant="ghost" @click="router.push('/teams/new')">返回创建 / 加入</AppButton><AppButton v-if="auth.user?.platformAdmin" variant="ghost" @click="router.push('/admin')">平台管理</AppButton><AppButton variant="ghost" @click="logout">退出登录</AppButton></div>
    <p v-if="msg" class="small" style="margin-top:10px">{{msg}}</p>
  </GlassPanel>
</div>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Hourglass } from 'lucide-vue-next'
import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'
import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'
const router = useRouter(), team = useTeam(), auth = useAuth()
const teamName = ref(sessionStorage.getItem('pending-team') ?? ''); const msg = ref('')
async function refresh(){ const before = team.teams.length; await team.loadTeams(); if (team.teams.length > before || team.currentId) { sessionStorage.removeItem('pending-team'); router.push('/workbench') } else msg.value = '还没有审批通过，请稍后再试。' }
async function logout(){ await auth.logout(); router.push('/login') }
</script>
<style scoped>
.wrap{max-width:560px;margin:0 auto;padding:40px 20px}
.card{text-align:center;padding:36px 26px;display:flex;flex-direction:column;align-items:center;gap:12px}
.ic{color:var(--theme)}.row{display:flex;gap:10px;flex-wrap:wrap;justify-content:center}
</style>
