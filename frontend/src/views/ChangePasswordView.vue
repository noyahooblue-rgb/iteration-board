<template>
<div class="wrap"><GlassPanel class="card">
  <h1 class="title">修改密码</h1>
  <p class="small" style="margin-top:6px">为了账号安全，请先把密码改成符合新规则的密码，改完才能继续使用平台。</p>
  <form @submit.prevent="submit" style="margin-top:14px">
    <AppInput v-model="f.old" type="password" label="当前密码" placeholder="请输入当前密码（或管理员给的临时密码）"/>
    <AppInput v-model="f.next" type="password" label="新密码" placeholder="至少 8 位，需同时包含字母、数字和特殊字符" style="margin-top:10px"/>
    <PasswordRules :password="f.next" :login-name="auth.user?.loginName"/>
    <AppInput v-model="f.confirm" type="password" label="确认新密码" placeholder="再输入一次新密码" style="margin-top:10px"/>
    <p v-if="err" class="errbox">{{ err }}</p>
    <AppButton style="width:100%;margin-top:14px" :disabled="busy" @click="submit">{{ busy ? '提交中…' : '保存新密码' }}</AppButton>
  </form>
  <p class="tiny" style="margin-top:12px;text-align:center">修改成功后，其他设备的登录会话会失效。</p>
</GlassPanel></div>
</template>
<script setup lang="ts">
// 修订六契约 5：强制改密页（修 rc2 死路 b）。路由守卫在 mustChangePassword 时把用户导到这里；
// 提交调既有 POST /api/v1/auth/change-password，成功后刷新 auth.user 再进工作台。
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'; import PasswordRules from '../components/PasswordRules.vue'
import { api, ApiError } from '../api/http'
import { useAuth } from '../stores/auth'; import { useToast } from '../stores/toast'
const router = useRouter(), auth = useAuth(), toast = useToast()
const f = reactive({ old: '', next: '', confirm: '' })
const err = ref(''), busy = ref(false)
async function submit() {
  err.value = ''
  if (!f.old) { err.value = '请输入当前密码'; return }
  const login = auth.user?.loginName ?? ''
  if (!(f.next.length >= 8 && f.next.length <= 64 && /[A-Za-z]/.test(f.next) && /\d/.test(f.next) && /[^A-Za-z0-9\s]/.test(f.next) && (!login || !f.next.toLowerCase().includes(login.toLowerCase())))) { err.value = '新密码不合规：至少 8 位，需同时包含字母、数字和特殊字符，且不能包含登录名'; return }
  if (f.next !== f.confirm) { err.value = '两次输入的新密码不一致'; return }
  busy.value = true
  try {
    await api.post('/auth/change-password', { oldPassword: f.old, newPassword: f.next })
    await auth.loadMe()
    toast.show('密码已修改')
    router.push('/workbench')
  } catch (e) { err.value = e instanceof ApiError ? e.message : '修改失败' } finally { busy.value = false }
}
</script>
<style scoped>
.wrap{min-height:100dvh;display:grid;place-items:center;padding:20px}
.wrap .card{width:440px;max-width:100%;padding:28px}
.title{font-size:20px}
.errbox{color:var(--c-red);background:var(--danger-bg);border-radius:10px;padding:8px 12px;font-size:13px;margin-top:12px}
</style>
