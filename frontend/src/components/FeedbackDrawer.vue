<template>
<Drawer :open="ui.feedbackOpen" @close="ui.feedbackOpen=false">
  <div class="fhead"><h2>意见反馈</h2><span style="flex:1"/><RouterLink to="/feedback" class="small link" @click="ui.feedbackOpen=false">查看全部反馈 →</RouterLink>
    <button class="icon press" aria-label="关闭" @click="ui.feedbackOpen=false"><X :size="18"/></button></div>
  <p class="warn">截图会被管理员看到，请先遮挡客户信息、密码等敏感内容；截图中不要包含生产数据和密钥。</p>
  <div class="fld">类型
    <div class="types"><button v-for="t in typeOpts" :key="t.v" class="tp press" :class="{on:form.type===t.v}" @click="form.type=t.v">{{t.label}}</button></div>
  </div>
  <AppInput v-model="form.title" label="标题（必填）" placeholder="一句话说清问题或建议" :error="errTitle" style="margin-top:12px"/>
  <div class="fld" @paste="onPaste">描述（必填，可直接粘贴截图）
    <textarea v-model="form.description" rows="5" placeholder="现象、复现步骤、期望效果…截图可 Ctrl+V 粘贴到这里"/>
  </div>
  <div class="fld">图片（最多 5 张，每张 ≤5MB）
    <div class="imgs">
      <span v-for="(im, i) in images" :key="i" class="imchip"><img v-if="im.preview" :src="im.preview" :alt="im.name"/><span class="imname">{{im.name}}</span><button class="press" aria-label="移除图片" @click="images.splice(i,1)"><X :size="13"/></button></span>
      <label v-if="images.length < 5" class="addimg press">＋ 选择图片<input type="file" accept=".png,.jpg,.jpeg,.webp,.gif" multiple hidden @change="onFiles"/></label>
    </div>
    <p v-if="imgErr" class="err">{{imgErr}}</p>
  </div>
  <p class="tiny" style="margin-top:12px">提交时自动附带：当前页面 {{pageUrl}} · 程序版本 {{version}} · 浏览器 {{browserInfo}}。每人每天最多提交 20 条。</p>
  <p v-if="err" class="err">{{err}}</p>
  <div class="foot"><span style="flex:1"/><AppButton variant="ghost" @click="ui.feedbackOpen=false">取消</AppButton><AppButton :disabled="submitting" @click="submit">{{ submitting ? '提交中…' : '提交反馈' }}</AppButton></div>
</Drawer>
</template>
<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { X } from 'lucide-vue-next'
import Drawer from './Drawer.vue'; import AppButton from './AppButton.vue'; import AppInput from './AppInput.vue'
import { api, ApiError, newIdempotencyKey } from '../api/http'
import { APP_VERSION } from '../utils/appMeta'
import { useUi } from '../stores/ui'; import { useToast } from '../stores/toast'; import { useAuth } from '../stores/auth'
const ui = useUi(), toast = useToast(), route = useRoute(), auth = useAuth()
const typeOpts = [{ v: 'ISSUE', label: '问题' }, { v: 'SUGGESTION', label: '建议' }, { v: 'OTHER', label: '其他' }] as const
const form = reactive({ type: 'ISSUE' as string, title: '', description: '' })
const images = ref<{ name: string; size: number; preview: string; file?: File }[]>([])
const err = ref(''), errTitle = ref(''), imgErr = ref(''), submitting = ref(false)
let idemKey = ''
const pageUrl = ref(''), version = APP_VERSION, browserInfo = navigator.userAgent
watch(() => ui.feedbackOpen, (open) => { if (open) { idemKey = newIdempotencyKey(); err.value = ''; errTitle.value = ''; imgErr.value = ''; pageUrl.value = route.fullPath } })
function addFiles(files: File[]) {
  imgErr.value = ''
  for (const f of files) {
    if (!['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(f.type)) { imgErr.value = '只支持 PNG / JPEG / WEBP / GIF 图片'; continue }
    if (f.size > 5 * 1024 * 1024) { imgErr.value = `「${f.name}」超过 5MB，已跳过`; continue }
    if (images.value.length >= 5) { imgErr.value = '图片最多 5 张'; break }
    const im = { name: f.name || '粘贴截图.png', size: f.size, preview: '', file: f }
    const rd = new FileReader(); rd.onload = () => { im.preview = String(rd.result ?? '') }; rd.readAsDataURL(f)
    images.value.push(im)
  }
}
function onFiles(e: Event) { const input = e.target as HTMLInputElement; if (input.files) addFiles([...input.files]); input.value = '' }
function onPaste(e: ClipboardEvent) { const files = [...(e.clipboardData?.items ?? [])].filter(i => i.type.startsWith('image/')).map(i => i.getAsFile()).filter(Boolean) as File[]; if (files.length) addFiles(files) }
async function submit() {
  errTitle.value = form.title.trim() ? '' : '标题必填'; if (errTitle.value) return
  if (!form.description.trim()) { err.value = '描述必填'; return }
  submitting.value = true; err.value = ''
  try {
    if (!idemKey) idemKey = newIdempotencyKey()
    // 契约（与后端 FeedbackController 一致）：图片随创建内联提交 [{fileName, contentType, contentBase64}]，≤5 张、≤5MB、image/*。
    const imgs: { fileName: string; contentType: string; contentBase64: string }[] = []
    for (const im of images.value) {
      if (!im.file) continue
      const dataUrl = await new Promise<string>((res, rej) => { const rd = new FileReader(); rd.onload = () => res(String(rd.result ?? '')); rd.onerror = rej; rd.readAsDataURL(im.file as File) })
      imgs.push({ fileName: im.name, contentType: im.file.type || 'image/png', contentBase64: dataUrl.includes(',') ? dataUrl.split(',')[1] : dataUrl })
    }
    const body: any = { type: form.type, title: form.title.trim(), description: form.description.trim(), pageUrl: pageUrl.value || route.fullPath, appVersion: version, browserInfo, images: imgs }
    await api.postKey('/feedback', body, idemKey)
    toast.show('反馈已提交，谢谢')
    form.title = ''; form.description = ''; images.value = []; idemKey = ''
    ui.feedbackOpen = false
  } catch (e) { err.value = e instanceof ApiError ? e.message : '提交失败' } finally { submitting.value = false }
}
void auth
</script>
<style scoped>
.fhead{display:flex;align-items:center;gap:10px}.fhead h2{font-size:18px}
.icon{width:36px;height:36px;display:grid;place-items:center;border-radius:10px;background:var(--fill)}
.warn{margin-top:10px;background:var(--warn-bg);border:1px solid var(--warn-brd);border-radius:10px;padding:8px 12px;font-size:13px}
.fld{display:block;font-size:12px;color:var(--text-2);margin-top:12px}
.fld textarea{width:100%;margin-top:6px;padding:9px 12px;border-radius:var(--r-ctl);border:1px solid var(--separator);background:var(--fill);resize:vertical;font-size:14px;color:var(--text)}
.types{display:flex;gap:8px;margin-top:6px}.tp{padding:7px 14px;border-radius:999px;border:1.5px solid var(--separator);font-weight:600;background:var(--card);color:var(--text)}
.tp.on{border-color:var(--theme);background:var(--theme-soft);color:var(--theme)}
.imgs{display:flex;gap:8px;flex-wrap:wrap;margin-top:6px}
.imchip{display:inline-flex;gap:6px;align-items:center;border:1px solid var(--field-brd);background:var(--field);border-radius:10px;padding:4px 8px 4px 4px;font-size:12px;max-width:220px}
.imchip img{width:30px;height:30px;object-fit:cover;border-radius:7px;border:1px solid var(--line)}
.imname{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.addimg{display:inline-flex;align-items:center;border:1.5px dashed var(--separator);border-radius:10px;padding:7px 12px;font-size:13px;font-weight:600;color:var(--theme);cursor:pointer}
.foot{display:flex;gap:10px;align-items:center;margin-top:16px}
.err{color:var(--c-red);font-size:12px;margin-top:8px}
.link{color:var(--theme);font-weight:600;text-decoration:none}
</style>
