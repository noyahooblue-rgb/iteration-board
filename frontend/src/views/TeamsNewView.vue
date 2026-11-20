<template>
<div class="wrap">
  <header class="top"><h1>加入或创建小组</h1><span class="topbtns"><button v-if="auth.user?.platformAdmin" class="small press" @click="router.push('/admin')">平台管理</button><button class="small press" @click="logout">退出登录</button></span></header>
  <p v-if="pending" class="pending glass">已提交加入申请，等待组长审批中。审批通过后刷新页面即可进入小组。</p>
  <div class="grid">
    <GlassPanel>
      <h2><Users :size="17"/> 创建小组</h2><p class="small">创建后你就是组长，并得到一个 8 位邀请码。</p>
      <AppInput v-model="c.name" label="小组名称" placeholder="给小组起个名字" style="margin-top:12px"/>
      <AppInput v-model="c.code" label="代号（2–5 个字母，事项编号前缀）" placeholder="如 SKY" style="margin-top:10px"/>
      <div class="fld">主题色<div class="colors"><button v-for="t in themes" :key="t.v" class="cdot press" :class="{on:c.themeColor===t.v}" :style="{background:t.c}" :aria-label="t.v" @click="c.themeColor=t.v"/></div></div>
      <label class="fld">迭代周期（周）<select v-model.number="c.iterationWeeks"><option :value="1">1 周</option><option :value="2">2 周</option><option :value="3">3 周</option><option :value="4">4 周</option></select></label>
      <label class="chk"><input type="checkbox" v-model="c.joinApproval"/> 加入需要组长审批</label>
      <p v-if="errC" class="err">{{errC}}</p>
      <AppButton style="width:100%;margin-top:12px" @click="create">创建小组</AppButton>
    </GlassPanel>
    <GlassPanel>
      <h2><KeyRound :size="17"/> 加入小组</h2><p class="small">输入组长给你的 8 位邀请码。</p>
      <AppInput v-model="j.code" label="邀请码" placeholder="8 位邀请码" style="margin-top:12px"/>
      <AppButton variant="ghost" style="margin-top:10px" @click="preview">预览小组</AppButton>
      <div v-if="previewData" class="prev">
        <p><b>{{previewData.team.name}}</b>（{{previewData.team.code}}）</p>
        <p class="small">组长：{{previewData.leaderName}} · {{previewData.team.joinApproval ? '加入需审批' : '凭码直接加入'}}</p>
        <p v-if="previewData.alreadyMember" class="small">你已是该小组成员。</p>
        <AppButton v-else style="margin-top:10px" @click="join">提交加入</AppButton>
      </div>
      <p v-if="errJ" class="err">{{errJ}}</p>
    </GlassPanel>
  </div>
</div>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Users, KeyRound } from 'lucide-vue-next'
import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'
import { api, ApiError } from '../api/http'; import { useTeam } from '../stores/team'; import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const router = useRouter(), team = useTeam(), auth = useAuth(), toast = useToast()
const c = reactive({ name:'', code:'', themeColor:'blue', iterationWeeks:2, joinApproval:true })
const j = reactive({ code:'' }); const errC = ref(''), errJ = ref(''), previewData = ref<any>(null), pending = ref(false)
const themes = [{v:'blue',c:'#007AFF'},{v:'indigo',c:'#5856D6'},{v:'violet',c:'#AF52DE'},{v:'pink',c:'#FF2D55'},{v:'orange',c:'#FF9500'},{v:'green',c:'#34C759'},{v:'teal',c:'#30B0C7'}]
async function create(){ errC.value=''; try{ const t = await api.post('/teams', c); toast.show(`小组已创建，邀请码 ${t.inviteCode ?? ''}`); await team.loadTeams(); router.push('/workbench') }catch(e){ errC.value=(e as ApiError).message } }
async function preview(){ errJ.value=''; try{ previewData.value = await api.get(`/teams/by-invite/${encodeURIComponent(j.code.trim())}`) }catch(e){ errJ.value=(e as ApiError).message; previewData.value=null } }
async function join(){ errJ.value=''; try{ const res = await api.post('/teams/join', { inviteCode: j.code.trim() }); if (res.joined){ await team.loadTeams(); router.push('/workbench') } else { sessionStorage.setItem('pending-team', previewData.value?.team?.name ?? res.team?.name ?? ''); router.push('/teams/pending') } }catch(e){ errJ.value=(e as ApiError).message } }
async function logout(){ await auth.logout(); router.push('/login') }
</script>
<style scoped>
.wrap{max-width:980px;margin:0 auto;padding:28px 20px}.top{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px}.topbtns{display:flex;gap:14px;align-items:center}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}@media(max-width:820px){.grid{grid-template-columns:1fr}}
h2{display:flex;gap:8px;align-items:center}
.fld{display:block;font-size:12px;color:var(--text-2);margin-top:12px}.fld select{width:100%;min-height:44px;margin-top:6px;border-radius:var(--r-ctl);border:1px solid var(--separator);background:var(--fill);padding:0 10px}
.colors{display:flex;gap:8px;margin-top:8px}.cdot{width:32px;height:32px;border-radius:50%;border:3px solid transparent}.cdot.on{border-color:var(--text)}
.chk{display:flex;gap:8px;align-items:center;margin-top:12px;font-size:13px}.chk input{width:17px;height:17px}
.err{color:var(--c-red);font-size:12px;margin-top:10px}.prev{margin-top:14px;background:var(--theme-soft);border-radius:12px;padding:12px}
.pending{border-radius:14px;padding:12px 16px;margin-bottom:14px}
</style>
