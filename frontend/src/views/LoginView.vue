<template>
<div class="wrap"><GlassPanel class="card">
  <span v-if="meta.demoMode" class="demo-badge"><i/>演示环境 · 数据为模拟</span>
  <div class="brand"><img class="lockup" :src="lockupSrc(effectiveTheme)" alt="迭代小组平台"/>
    <p class="small sub">{{ meta.demoMode && DEMO_SUBTITLE ? DEMO_SUBTITLE : '小组的事项、进展与阻塞，一眼看清。' }}</p></div>
  <SegmentedControl v-model="tab" :options="[{label:'登录',value:'login'},{label:'注册',value:'register'}]" class="tabs"/>
  <template v-if="tab==='login'">
    <AppInput v-model="f.loginName" data-testid="login-form" label="登录名" placeholder="请输入登录名" :error="e.loginName"/>
    <AppInput v-model="f.password" label="密码" type="password" placeholder="请输入密码" :error="e.password" style="margin-top:10px"/>
    <div v-if="needCaptcha" class="caprow">
      <AppInput v-model="f.captchaCode" label="图形验证码" placeholder="4 位字符"/>
      <button class="cap press" @click="loadCaptcha" title="点击刷新" v-html="captchaSvg"/>
    </div>
    <p v-if="lockedMin" class="errbox">账号已锁定，还剩约 {{lockedMin}} 分钟再试。</p>
    <p v-if="err" class="errbox">{{err}}</p>
    <AppButton style="width:100%;margin-top:14px" :disabled="busy" @click="login">{{ busy ? '登录中…' : '登录' }}</AppButton>
    <div v-if="meta.demoMode" class="demo-box">
      <p class="small">{{ DEMO_FILL_HINT }}</p>
      <div class="drow"><button v-for="d in demos" :key="d.login" class="chip press" @click="fillDemo(d.login)"><span class="chip-av" :style="{background:d.color}">{{d.name.slice(0,1)}}</span>{{d.name}} · {{d.label}}<span class="chip-login">{{d.login}}</span></button></div>
    </div>
  </template>
  <template v-else>
    <div v-if="!meta.registrationOpen" class="reg-closed">
      <span class="reg-ico"><UserRoundX :size="26"/></span>
      <p class="big">演示环境已关闭开放注册</p>
      <p class="small">{{ DEMO_REG_CLOSED_TEXT }}</p>
      <AppButton @click="tab='login'">去登录</AppButton>
    </div>
    <template v-else>
      <AppInput v-model="r.loginName" label="登录名（3–32 位，注册后不可改）" :error="e.rLogin"/>
      <AppInput v-model="r.displayName" label="显示姓名" style="margin-top:10px"/>
      <AppInput v-model="r.password" label="密码（至少 8 位，需同时包含字母、数字和特殊字符）" type="password" style="margin-top:10px"/>
      <PasswordRules :password="r.password" :login-name="r.loginName"/>
      <div class="strength"><div :style="{width:strength*25+'%',background:strengthColor}"/><span class="tiny">{{['','弱','一般','较强','强'][strength]}}</span></div>
      <div class="caprow"><AppInput v-model="r.captchaCode" label="图形验证码"/><button class="cap press" @click="loadCaptcha" title="点击刷新" v-html="captchaSvg"/></div>
      <p v-if="err" class="errbox">{{err}}</p>
      <AppButton style="width:100%;margin-top:14px" :disabled="busy" @click="register">{{ busy ? '提交中…' : '注册并登录' }}</AppButton>
    </template>
  </template>
  <p class="tiny foot">请勿录入客户信息、生产数据、密码和密钥。</p>
  <p class="tiny foot" style="margin-top:4px">版本 {{version}} · 构建日期 {{buildDate}}</p>
</GlassPanel></div>
</template>
<script setup lang="ts">
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { UserRoundX } from 'lucide-vue-next'
import GlassPanel from '../components/GlassPanel.vue'; import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'; import SegmentedControl from '../components/SegmentedControl.vue'; import PasswordRules from '../components/PasswordRules.vue'
import { api, ApiError, DEMO_ACCOUNTS, DEMO_SUBTITLE, DEMO_FILL_HINT, DEMO_REG_CLOSED_TEXT, DEMO_PASSWORD_FILL } from '../api/http'; import { useAuth } from '../stores/auth'; import { useTeam } from '../stores/team'; import { useToast } from '../stores/toast'
const router = useRouter(), route = useRoute(), auth = useAuth(), team = useTeam(), toast = useToast()
import { APP_VERSION, BUILD_DATE } from '../utils/appMeta'
import { effectiveTheme, lockupSrc } from '../utils/appearance'
// 登录页底部版本（修订三二.1）：优先用后端 /meta 的版本，离线兜底用本地常量。
const version = computed(()=> (auth.meta as any).version ?? APP_VERSION)
const buildDate = computed(()=> (auth.meta as any).buildDate ?? BUILD_DATE)
const tab = ref('login'), err = ref(''), lockedMin = ref(0), needCaptcha = ref(false), busy = ref(false)
const meta = computed(()=>auth.meta)
const f = reactive({ loginName:'', password:'', captchaCode:'' }); const r = reactive({ loginName:'', displayName:'', password:'', captchaCode:'' })
const e = reactive<any>({})
const captchaId = ref(''), captchaSvg = ref('')
const demos = DEMO_ACCOUNTS
const strength = computed(()=> { let s=0; if(r.password.length>=8)s++; if(/[A-Za-z]/.test(r.password)&&/\d/.test(r.password))s++; if(r.password.length>=12)s++; if(/[^A-Za-z0-9]/.test(r.password))s++; return s })
const strengthColor = computed(()=> ['var(--strength-empty)','var(--c-red)','var(--c-adhoc)','var(--c-task)','var(--c-green)'][strength.value])
async function loadCaptcha(){ try{ const d = await api.get('/auth/captcha'); captchaId.value=d.captchaId; captchaSvg.value=d.imageSvg }catch{} }
onMounted(()=>{ auth.loadMeta(); loadCaptcha() })
function fillDemo(login:string){ f.loginName=login; f.password=DEMO_PASSWORD_FILL; toast.show('已填入演示账号，点登录即可') }
async function afterLogin(){ await team.loadTeams(); const redirect = route.query.redirect; if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') && redirect !== '/login') { router.push(redirect); return } router.push(team.teams.length ? '/workbench' : '/teams/new') }
async function login(){ if (busy.value) return; err.value=''; e.loginName = f.loginName ? '' : '请输入登录名'; e.password = f.password ? '' : '请输入密码'; if(e.loginName||e.password) return
  busy.value = true
  try{ auth.user = await api.post('/auth/login', { loginName:f.loginName, password:f.password, captchaId: needCaptcha.value? captchaId.value:undefined, captchaCode: needCaptcha.value? f.captchaCode:undefined }); await afterLogin() }
  catch(ex){ const a = ex as ApiError; err.value=a.message; if (a.status===423) lockedMin.value = a.detail?.remainingMinutes ?? 15; if (a.detail?.needCaptcha || (a.detail?.failCount>=3)) { needCaptcha.value=true; loadCaptcha() } if (a.detail?.failCount>=3) needCaptcha.value=true } finally { busy.value = false } }
async function register(){ if (busy.value) return; err.value=''; e.rLogin = r.loginName.length>=3 ? '' : '登录名至少 3 位'; if(e.rLogin) return
  busy.value = true
  try{ await api.post('/auth/register', { ...r, captchaId: captchaId.value }); await auth.loadMe(); await afterLogin() }
  catch(ex){ err.value=(ex as ApiError).message; loadCaptcha() } finally { busy.value = false } }
</script>
<style scoped>
.wrap{min-height:100dvh;display:grid;place-items:center;padding:20px}
.wrap .card{width:420px;max-width:100%;padding:32px 32px 24px;position:relative}
.demo-badge{position:absolute;top:-14px;right:18px;display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--text-2);padding:5px 10px;border-radius:999px;background:var(--field);border:1px solid var(--field-brd);white-space:nowrap}
.demo-badge i{width:7px;height:7px;border-radius:50%;background:var(--c-yellow);box-shadow:var(--halo-yellow);flex:none}
.brand{display:flex;flex-direction:column;gap:6px;align-items:flex-start}
.brand .lockup{width:264px;max-width:82%;height:auto;display:block;border-radius:10px}
.sub{margin:2px 0 0}
.tabs{display:flex;width:100%;margin:22px 0 20px;padding:4px;border-radius:14px}
.tabs :deep(button){flex:1;min-height:36px;font-size:14px;border-radius:10px}
.caprow{display:flex;gap:10px;align-items:flex-end;margin-top:10px}.caprow>*:first-child{flex:1}
.cap{height:44px;border-radius:12px;overflow:hidden;border:1px solid var(--separator);padding:0;background:var(--card-solid)}
.strength{display:flex;align-items:center;gap:8px;margin-top:8px}.strength>div{height:6px;border-radius:99px;transition:width .3s;min-width:0;background:var(--fill);flex:none;width:0}
.strength>div{max-width:180px}
.errbox{color:var(--c-red);background:var(--danger-bg);border-radius:10px;padding:8px 12px;font-size:13px;margin-top:12px}
.demo-box{margin-top:18px;padding-top:14px;border-top:1px solid var(--field-brd)}
.drow{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.chip{display:inline-flex;align-items:center;gap:8px;border:1px solid var(--field-brd);background:var(--field);border-radius:999px;padding:6px 12px 6px 6px;font-size:13px;font-weight:500;min-height:38px}
.chip-av{width:26px;height:26px;border-radius:50%;color:var(--on-color);display:grid;place-items:center;font-size:12px;font-weight:600;flex:none}
.chip-login{color:var(--text-3);font-size:12px}
.reg-closed{text-align:center;display:flex;flex-direction:column;align-items:center;gap:10px;padding:8px 0}
.reg-ico{width:52px;height:52px;border-radius:16px;display:grid;place-items:center;color:var(--theme);background:var(--sel)}
.reg-closed .big{font-size:16px;font-weight:700;margin:0}
.reg-closed .small{margin:0}
.foot{margin-top:16px;text-align:center}
@media(max-width:520px){.wrap .card{padding:26px 22px 20px}}
</style>
