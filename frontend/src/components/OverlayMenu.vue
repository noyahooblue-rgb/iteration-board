<template>
<Teleport to="body">
  <div v-if="open" ref="panel" class="ovl" :style="panelStyle" role="menu" @keydown="onPanelKey">
    <slot/>
  </div>
</Teleport>
</template>
<script setup lang="ts">
// 修订五 A：全平台统一浮层组件。
// - Teleport 到 body 顶层渲染，绝不放在抽屉/弹窗/卡片内部（避免 overflow/transform 裁切）；
// - 实色 var(--bg-raised) 不透明背景 + 1px var(--line) 描边 + shadow-3，禁止 backdrop-filter；
// - 打开时几何自检：贴触发元素，空间不足自动翻转（下→上、右→左），平移保证完整在视口内且距边缘 ≥8px；
// - 滚动/缩放时跟随重定位；Esc 先关浮层（捕获阶段拦截，不关抽屉/弹窗）；点外部关闭；
// - 键盘 ↑↓ 移动、Enter 选择、Esc 关闭；关闭后焦点回到触发元素。
import { ref, watch, nextTick, onUnmounted } from 'vue'
const props = withDefaults(defineProps<{
  open: boolean
  anchor: HTMLElement | null
  align?: 'start' | 'end'
  minWidth?: number
  /** true 时不抢焦点（如 @提及列表：焦点必须留在输入框里继续打字，键盘由输入框自己处理） */
  manualFocus?: boolean
}>(), { align: 'start' })
const emit = defineEmits(['close'])
const panel = ref<HTMLElement | null>(null)
const panelStyle = ref<Record<string, string>>({ visibility: 'hidden', left: '0px', top: '0px' })

function place() {
  const el = panel.value, a = props.anchor
  if (!el || !a) return
  const r = a.getBoundingClientRect()
  const vw = window.innerWidth, vh = window.innerHeight
  const w = Math.max(el.offsetWidth, props.minWidth ?? 0)
  const h = el.offsetHeight
  // 水平：按 align 贴触发元素，越界则翻转对齐/平移，并保证距视口边缘 ≥8px
  let x = props.align === 'end' ? r.right - w : r.left
  if (x + w > vw - 8) x = vw - 8 - w
  if (x < 8) x = 8
  // 垂直：默认向下，空间不足翻到上方；仍不足则钳制并限高滚动
  let y = r.bottom + 4
  let maxH = ''
  if (y + h > vh - 8) {
    const up = r.top - 4 - h
    if (up >= 8) y = up
    else { y = Math.max(8, vh - 8 - h); if (h > vh - 16) maxH = (vh - 16) + 'px' }
  }
  // 最终钳制：锚点本身在视口外（如侧栏底部按钮被挤出）时，翻转结果也要完整留在视口内
  if (y + h > vh - 8) y = Math.max(8, vh - 8 - h)
  if (y < 8) y = 8
  panelStyle.value = { visibility: 'visible', left: x + 'px', top: y + 'px', ...(maxH ? { maxHeight: maxH, overflowY: 'auto' } : {}) }
}
function onScrollResize() { if (props.open) place() }
function onDocPointer(e: PointerEvent) {
  if (!props.open) return
  const t = e.target as Node
  if (panel.value?.contains(t) || props.anchor?.contains(t)) return
  emit('close')
}
function onDocKey(e: KeyboardEvent) {
  if (!props.open || e.key !== 'Escape') return
  // 捕获阶段先于抽屉/弹窗的 Esc 处理：只关浮层
  e.preventDefault(); e.stopPropagation()
  emit('close')
}
function onPanelKey(e: KeyboardEvent) {
  if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
  const items = Array.from(panel.value?.querySelectorAll<HTMLElement>('button, a[href], [data-ovl-item]') ?? [])
    .filter(el => !el.hasAttribute('disabled') && el.offsetParent !== null)
  if (!items.length) return
  e.preventDefault()
  const idx = items.indexOf(document.activeElement as HTMLElement)
  const next = e.key === 'ArrowDown' ? items[(idx + 1) % items.length] : items[(idx - 1 + items.length) % items.length]
  next.focus()
}
function bind() {
  window.addEventListener('resize', onScrollResize)
  window.addEventListener('scroll', onScrollResize, true)
  document.addEventListener('pointerdown', onDocPointer, true)
  document.addEventListener('keydown', onDocKey, true)
}
function unbind() {
  window.removeEventListener('resize', onScrollResize)
  window.removeEventListener('scroll', onScrollResize, true)
  document.removeEventListener('pointerdown', onDocPointer, true)
  document.removeEventListener('keydown', onDocKey, true)
}
watch(() => props.open, async (v) => {
  if (v) {
    panelStyle.value = { visibility: 'hidden', left: '0px', top: '0px' }
    bind()
    await nextTick()
    place()
    if (props.manualFocus) return
    // 打开后把焦点移入浮层第一项（菜单键盘操作的前提）；带输入框的浮层优先聚焦输入框
    const el = panel.value
    const first = el?.querySelector<HTMLElement>('input, textarea, select, [data-ovl-autofocus]') ??
      el?.querySelector<HTMLElement>('button, a[href], [data-ovl-item]')
    first?.focus()
  } else {
    unbind()
    // 关闭后焦点回触发元素（仅当焦点还在浮层内/已丢失时；manualFocus 浮层不抢焦点也不回焦）
    if (!props.manualFocus && props.anchor && (document.activeElement === document.body || panel.value?.contains(document.activeElement))) {
      props.anchor.focus()
    }
  }
})
onUnmounted(unbind)
</script>
<style scoped>
.ovl{position:fixed;z-index:var(--z-overlay);background:var(--bg-raised);border:1px solid var(--line);border-radius:14px;box-shadow:var(--shadow-3);padding:6px;color:var(--text);min-width:150px}
</style>
