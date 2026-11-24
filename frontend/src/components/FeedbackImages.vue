<template>
  <!-- 修订六契约 3：反馈图片。visible=false 且有图时只显示可见范围提示；visible 时缩略图 + 大图查看器。 -->
  <p v-if="!visible && images.length" class="tiny" style="margin-top:6px">含 {{ images.length }} 张图片（仅提交人和管理员可见）</p>
  <div v-else-if="images.length" class="thumbs">
    <template v-for="(im, i) in images" :key="im.id">
      <div v-if="failed[im.id] && !srcs[im.id]" class="thumb thumb-fail">
        <span class="tiny">图片加载失败</span>
        <button class="retry press" @click.stop="retry(im)">重试</button>
      </div>
      <button v-else class="thumb press" @click="viewerIdx = i" :title="im.fileName">
        <img v-if="srcs[im.id]" :src="srcs[im.id]" :alt="im.fileName"/>
        <span v-else class="tiny">{{ loadingIds.has(im.id) ? '加载中…' : im.fileName }}</span>
      </button>
    </template>
  </div>
  <Teleport to="body"><Transition name="fbv"><div v-if="viewerIdx !== null && current" class="vmask" @click.self="viewerIdx = null">
    <div class="vbox glass" role="dialog" aria-modal="true">
      <div class="vhead"><span class="small">{{ current.fileName }}（{{ viewerIdx + 1 }} / {{ images.length }}）</span><span style="flex:1"/>
        <button class="vbtn press" :disabled="downloading" @click="downloadCurrent">下载</button>
        <button class="vbtn press" aria-label="关闭" @click="viewerIdx = null"><X :size="17"/></button></div>
      <p v-if="downloadError" class="small">{{ downloadError }}</p>
      <img v-if="srcs[current.id]" class="vimg" :src="srcs[current.id]" :alt="current.fileName"/>
      <div v-else-if="failed[current.id]" class="vfail">
        <p class="small">图片加载失败：{{ failed[current.id] }}</p>
        <button class="vbtn press" @click="retry(current)">重试</button>
      </div>
      <p v-else class="small">加载中…</p>
      <div class="vfoot">
        <button class="vbtn press" :disabled="viewerIdx <= 0" @click="viewerIdx = viewerIdx! - 1"><ChevronLeft :size="16"/>上一张</button>
        <button class="vbtn press" :disabled="viewerIdx >= images.length - 1" @click="viewerIdx = viewerIdx! + 1">下一张<ChevronRight :size="16"/></button>
      </div>
    </div>
  </div></Transition></Teleport>
</template>
<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { X, ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { api, apiMode } from '../api/http'
import type { FeedbackImage } from '../api/types'
const props = defineProps<{ feedbackId: number; images: FeedbackImage[]; visible: boolean }>()
const srcs = ref<Record<number, string>>({})
const failed = ref<Record<number, string>>({})
const loadingIds = ref<Set<number>>(new Set())
const downloading = ref(false)
const downloadError = ref('')
const viewerIdx = ref<number | null>(null)
const current = computed(() => viewerIdx.value != null ? props.images[viewerIdx.value] ?? null : null)
const objectUrls = new Set<string>()
function imageUrl(id: number) { return `/api/v1/feedback/${props.feedbackId}/images/${id}` }
function setLoading(id: number, on: boolean) { const s = new Set(loadingIds.value); if (on) s.add(id); else s.delete(id); loadingIds.value = s }
async function parseErrorMessage(res: Response): Promise<string> {
  try { const d: any = await res.clone().json(); if (d?.message) return String(d.message) } catch {}
  return `请求失败（错误 ${res.status}）`
}
async function fetchImage(im: FeedbackImage, reload = false) {
  if (apiMode === 'mock') {
    setLoading(im.id, true)
    try { const r: any = await api.get(`/feedback/${props.feedbackId}/images/${im.id}`); if (r?.dataUrl) { srcs.value[im.id] = r.dataUrl; delete failed.value[im.id] } else { failed.value[im.id] = '图片加载失败' } }
    catch (e: any) { failed.value[im.id] = e?.message ?? '图片加载失败' }
    finally { setLoading(im.id, false) }
    return
  }
  setLoading(im.id, true)
  try {
    const res = await fetch(imageUrl(im.id), { credentials: 'include', ...(reload ? { cache: 'reload' as RequestCache } : {}) })
    if (!res.ok) throw new Error(await parseErrorMessage(res))
    const ct = res.headers.get('Content-Type') ?? ''
    if (!ct.toLowerCase().startsWith('image/')) throw new Error('服务器返回的不是图片内容')
    const blob = await res.blob()
    const old = srcs.value[im.id]
    if (old && objectUrls.has(old)) { URL.revokeObjectURL(old); objectUrls.delete(old) }
    const url = URL.createObjectURL(blob); objectUrls.add(url); srcs.value[im.id] = url; delete failed.value[im.id]
  } catch (e: any) { failed.value[im.id] = e?.message ?? '图片加载失败' }
  finally { setLoading(im.id, false) }
}
async function loadSrcs() {
  if (!props.visible) return
  for (const im of props.images) {
    if (srcs.value[im.id] || loadingIds.value.has(im.id)) continue
    await fetchImage(im)
  }
}
function retry(im: FeedbackImage) { return fetchImage(im, true) }
watch(() => [props.feedbackId, props.visible, props.images.length], loadSrcs, { immediate: true })
function parseContentDispositionFilename(cd: string | null, fallback: string): string {
  if (!cd) return fallback
  const star = cd.match(/filename\*\s*=\s*(?:[^']*)'[^']*'([^;]+)/i)
  if (star?.[1]) { try { return decodeURIComponent(star[1].trim().replace(/^"|"$/g, '')) } catch { return star[1].trim().replace(/^"|"$/g, '') } }
  const plain = cd.match(/filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;]+)/i)
  if (plain) return (plain[1] ?? plain[2] ?? '').trim() || fallback
  return fallback
}
async function downloadCurrent() {
  const cur = current.value; if (!cur || downloading.value) return
  downloadError.value = ''
  if (apiMode === 'mock') {
    const href = srcs.value[cur.id]; if (!href) { downloadError.value = failed.value[cur.id] ?? '图片尚未加载完成'; return }
    const a = document.createElement('a'); a.href = href; a.download = cur.fileName; document.body.appendChild(a); a.click(); a.remove(); return
  }
  downloading.value = true
  try {
    const res = await fetch(imageUrl(cur.id), { credentials: 'include' })
    if (!res.ok) throw new Error(await parseErrorMessage(res))
    const ct = (res.headers.get('Content-Type') ?? '').toLowerCase()
    if (!(ct.startsWith('image/') || ct.startsWith('application/octet-stream'))) {
      let reason = '服务器返回的不是图片文件'
      if (ct.includes('application/json')) { try { const d: any = await res.clone().json(); if (d?.message) reason = String(d.message) } catch {} }
      throw new Error(reason)
    }
    const blob = await res.blob()
    const fileName = parseContentDispositionFilename(res.headers.get('Content-Disposition'), cur.fileName)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = fileName; document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (e: any) { downloadError.value = e?.message ?? '下载失败' }
  finally { downloading.value = false }
}
function onKey(e: KeyboardEvent) {
  const idx = viewerIdx.value
  if (idx == null) return
  if (e.key === 'Escape') viewerIdx.value = null
  if (e.key === 'ArrowLeft' && idx > 0) viewerIdx.value = idx - 1
  if (e.key === 'ArrowRight' && idx < props.images.length - 1) viewerIdx.value = idx + 1
}
watch(viewerIdx, (v) => { downloadError.value = ''; if (v != null) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey) })
onUnmounted(() => { window.removeEventListener('keydown', onKey); for (const u of objectUrls) URL.revokeObjectURL(u); objectUrls.clear() })
</script>
<style scoped>
.thumbs{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.thumb{width:76px;height:76px;border-radius:10px;overflow:hidden;border:1px solid var(--field-brd);background:var(--field);display:grid;place-items:center;padding:0}
.thumb img{width:100%;height:100%;object-fit:cover;display:block}
.thumb-fail{display:flex;flex-direction:column;gap:4px;align-items:center;justify-content:center;padding:4px;text-align:center}
.retry{border:1px solid var(--field-brd);background:var(--field);border-radius:8px;padding:2px 8px;font-size:12px;font-weight:600;color:var(--text);min-height:26px}
.vmask{position:fixed;inset:0;background:var(--mask-strong);z-index:var(--z-modal);display:grid;place-items:center;padding:18px}
.vbox{max-width:min(920px,94vw);max-height:92dvh;border-radius:var(--r-panel);padding:14px;display:flex;flex-direction:column;gap:10px}
.vhead{display:flex;gap:10px;align-items:center}
.vimg{max-width:100%;max-height:70dvh;object-fit:contain;border-radius:12px;background:var(--field)}
.vfail{display:flex;flex-direction:column;gap:10px;align-items:center;padding:24px 0}
.vfoot{display:flex;gap:10px;justify-content:center}
.vbtn{display:inline-flex;gap:5px;align-items:center;border:1px solid var(--field-brd);background:var(--field);border-radius:10px;padding:6px 12px;font-size:13px;font-weight:600;color:var(--text);text-decoration:none;min-height:34px}
.vbtn:disabled{opacity:.45}
.fbv-enter-active{transition:opacity .16s ease}.fbv-enter-from{opacity:0}.fbv-leave-to{opacity:0}
</style>
