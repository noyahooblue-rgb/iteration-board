<template>
  <!-- 修订六契约 3：反馈图片。visible=false 且有图时只显示可见范围提示；visible 时缩略图 + 大图查看器。 -->
  <p v-if="!visible && images.length" class="tiny" style="margin-top:6px">含 {{ images.length }} 张图片（仅提交人和管理员可见）</p>
  <div v-else-if="images.length" class="thumbs">
    <button v-for="(im, i) in images" :key="im.id" class="thumb press" @click="viewerIdx = i" :title="im.fileName">
      <img v-if="srcs[im.id]" :src="srcs[im.id]" :alt="im.fileName"/>
      <span v-else class="tiny">{{ im.fileName }}</span>
    </button>
  </div>
  <Teleport to="body"><Transition name="fbv"><div v-if="viewerIdx !== null && current" class="vmask" @click.self="viewerIdx = null">
    <div class="vbox glass" role="dialog" aria-modal="true">
      <div class="vhead"><span class="small">{{ current.fileName }}（{{ viewerIdx + 1 }} / {{ images.length }}）</span><span style="flex:1"/>
        <a v-if="srcs[current.id]" class="vbtn" :href="srcs[current.id]" :download="current.fileName">下载</a>
        <button class="vbtn press" aria-label="关闭" @click="viewerIdx = null"><X :size="17"/></button></div>
      <img class="vimg" :src="srcs[current.id]" :alt="current.fileName"/>
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
const viewerIdx = ref<number | null>(null)
const current = computed(() => viewerIdx.value != null ? props.images[viewerIdx.value] ?? null : null)
// real 模式：图片接口同源 cookie 直出，用接口地址作 img src；
// mock 模式：图片是页面内模拟后端、没有真实 URL，改走 api 取占位 dataUrl（契约 7 最小实现）。
async function loadSrcs() {
  if (!props.visible) return
  for (const im of props.images) {
    if (srcs.value[im.id]) continue
    if (apiMode === 'mock') {
      try { const r: any = await api.get(`/feedback/${props.feedbackId}/images/${im.id}`); if (r?.dataUrl) srcs.value[im.id] = r.dataUrl } catch {}
    } else {
      srcs.value[im.id] = `/api/v1/feedback/${props.feedbackId}/images/${im.id}`
    }
  }
}
watch(() => [props.feedbackId, props.visible, props.images.length], loadSrcs, { immediate: true })
function onKey(e: KeyboardEvent) {
  const idx = viewerIdx.value
  if (idx == null) return
  if (e.key === 'Escape') viewerIdx.value = null
  if (e.key === 'ArrowLeft' && idx > 0) viewerIdx.value = idx - 1
  if (e.key === 'ArrowRight' && idx < props.images.length - 1) viewerIdx.value = idx + 1
}
watch(viewerIdx, (v) => { if (v != null) window.addEventListener('keydown', onKey); else window.removeEventListener('keydown', onKey) })
onUnmounted(() => window.removeEventListener('keydown', onKey))
</script>
<style scoped>
.thumbs{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
.thumb{width:76px;height:76px;border-radius:10px;overflow:hidden;border:1px solid var(--field-brd);background:var(--field);display:grid;place-items:center;padding:0}
.thumb img{width:100%;height:100%;object-fit:cover;display:block}
.vmask{position:fixed;inset:0;background:var(--mask-strong);z-index:var(--z-modal);display:grid;place-items:center;padding:18px}
.vbox{max-width:min(920px,94vw);max-height:92dvh;border-radius:var(--r-panel);padding:14px;display:flex;flex-direction:column;gap:10px}
.vhead{display:flex;gap:10px;align-items:center}
.vimg{max-width:100%;max-height:70dvh;object-fit:contain;border-radius:12px;background:var(--field)}
.vfoot{display:flex;gap:10px;justify-content:center}
.vbtn{display:inline-flex;gap:5px;align-items:center;border:1px solid var(--field-brd);background:var(--field);border-radius:10px;padding:6px 12px;font-size:13px;font-weight:600;color:var(--text);text-decoration:none;min-height:34px}
.vbtn:disabled{opacity:.45}
.fbv-enter-active{transition:opacity .16s ease}.fbv-enter-from{opacity:0}.fbv-leave-to{opacity:0}
</style>
