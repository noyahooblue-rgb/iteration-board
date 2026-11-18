<template>
<AppShell title="组件展示">
<div class="grid">
  <section class="panel glass"><h3>玻璃面板</h3><p class="sub">导航与浮层专用：55% 半透明底 + 20px 模糊 + 饱和度 180% + 1px 亮边（顶边更亮）。</p>
    <p><span class="demo-badge"><i/>演示环境 · 数据为模拟</span></p>
    <p class="note">内容卡片则使用 86% 不透明实底、不加模糊，左侧带 4px 分类色条，如下方示例。</p></section>

  <section class="panel glass"><h3>内容卡片</h3><p class="sub">圆角 16px · 内边距 16px · 左侧分类色条</p>
    <article class="demo-card"><div class="dc-top"><span class="small">EX-25</span><span class="prio P0">P0</span></div>
      <p class="dc-title">示例事项 · 导出偶发空白</p>
      <div class="dc-meta"><Avatar name="苏某" color="#FF9500" :size="22"/><span class="due over"><Calendar :size="13"/> 11月16日 · 已逾期</span><span class="meta-ico"><MessageSquare :size="14"/> 2</span></div></article></section>

  <section class="panel glass"><h3>按钮</h3><p class="sub">圆角 12px · 按压缩放 0.97 回弹</p>
    <div class="row"><AppButton>主按钮</AppButton><AppButton variant="ghost">次按钮</AppButton><AppButton variant="danger">危险按钮</AppButton></div>
    <div class="row"><AppButton variant="text">幽灵按钮</AppButton><AppButton disabled>禁用状态</AppButton></div></section>

  <section class="panel glass"><h3>输入框</h3><p class="sub">圆角 12px · 聚焦蓝色描边</p>
    <AppInput v-model="v" label="普通输入" placeholder="请输入内容"/>
    <AppInput v-model="vbad" label="错误状态" error="这里是字段下方的错误提示" style="margin-top:12px"/></section>

  <section class="panel glass"><h3>状态胶囊与类型徽标</h3><p class="sub">六种状态 · 五种事项类型</p>
    <div class="row"><StatusPill v-for="st in statuses" :key="st" :status="st"/></div>
    <div class="row"><TypeBadge v-for="t in types" :key="t" :type="t"/></div>
    <div class="row"><span class="prio P0">P0</span><span class="prio P1">P1</span><span class="prio P2">P2</span><span class="prio P3">P3</span><span class="tag">标签示例</span></div></section>

  <section class="panel glass"><h3>进度环与进度条</h3><p class="sub">数字使用等宽数字特性</p>
    <div class="rings"><div><ProgressRing :value="72" :size="84" color="#007AFF"/><p>迭代完成度</p></div>
      <div><ProgressRing :value="100" :size="84" color="#34C759"/><p>已完成</p></div>
      <div><ProgressRing :value="35" :size="84" color="#FF9500"/><p>进行中</p></div></div>
    <ProgressBar :value="58" style="margin-top:16px"/><ProgressBar :value="100" color="var(--c-green)" style="margin-top:10px"/></section>

  <section class="panel glass"><h3>头像与开关</h3><p class="sub">头像为姓名首字 + 彩色底</p>
    <div class="row"><Avatar v-for="a in avatars" :key="a.n" :name="a.n" :color="a.c" :size="36"/></div>
    <div class="row"><span class="stack"><Avatar v-for="a in avatars.slice(0,4)" :key="a.n" :name="a.n" :color="a.c" :size="28" class="stk"/></span><span class="small">协作人堆叠</span></div>
    <div class="row"><label class="chk">开关示例<span class="switch"><input type="checkbox" checked/><span class="tr"/></span></label>
      <label class="chk">关闭态<span class="switch"><input type="checkbox"/><span class="tr"/></span></label></div>
    <div class="row"><SegmentedControl v-model="seg" :options="[{label:'周',value:'w'},{label:'月',value:'m'}]"/><AppButton variant="ghost" @click="m=true">打开弹窗</AppButton><AppButton variant="ghost" @click="d=true">打开抽屉</AppButton></div></section>

  <section class="panel glass"><h3>分类色板</h3><p class="sub">事项类型五色</p>
    <div class="swatches"><div v-for="sw in typeSwatches" :key="sw.n" class="swatch"><i :style="{background:sw.c}"/><div><b>{{sw.n}}</b><span>{{sw.c}}</span></div></div></div></section>

  <section class="panel glass"><h3>语义色板</h3><p class="sub">状态语义与主题色</p>
    <div class="swatches"><div v-for="sw in semSwatches" :key="sw.n" class="swatch"><i :style="{background:sw.c}"/><div><b>{{sw.n}}</b><span>{{sw.c}}</span></div></div></div></section>
</div>
  <Modal :open="m" @close="m=false"><h2>弹窗</h2><p class="small">约 350ms 带轻微回弹的入场，只动 transform / opacity。</p><AppButton @click="m=false" style="margin-top:12px">关闭</AppButton></Modal>
  <Drawer :open="d" @close="d=false"><h2>抽屉</h2><p class="small">宽 560px，窄屏全屏。</p></Drawer>
</AppShell>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { Calendar, MessageSquare } from 'lucide-vue-next'
import AppShell from '../components/AppShell.vue'
import AppButton from '../components/AppButton.vue'; import AppInput from '../components/AppInput.vue'
import TypeBadge from '../components/TypeBadge.vue'; import StatusPill from '../components/StatusPill.vue'; import Avatar from '../components/Avatar.vue'
import ProgressBar from '../components/ProgressBar.vue'; import ProgressRing from '../components/ProgressRing.vue'; import SegmentedControl from '../components/SegmentedControl.vue'
import Modal from '../components/Modal.vue'; import Drawer from '../components/Drawer.vue'
import type { ItemType, ItemStatus } from '../api/types'
const v = ref(''), vbad = ref('错误示例'), seg = ref('w'), m = ref(false), d = ref(false)
const types: ItemType[] = ['TASK','STORY','DEFECT','ANALYSIS','ADHOC']
const statuses: ItemStatus[] = ['UNCLAIMED','NOT_STARTED','IN_PROGRESS','IN_REVIEW','DONE','CANCELLED']
const avatars = [{n:'林某',c:'#007AFF'},{n:'陈某',c:'#5856D6'},{n:'苏某',c:'#FF9500'},{n:'王某',c:'#30B0C7'},{n:'赵某',c:'#AF52DE'}]
const typeSwatches = [{n:'需求',c:'#007AFF'},{n:'任务',c:'#30B0C7'},{n:'缺陷',c:'#FF2D55'},{n:'分析',c:'#AF52DE'},{n:'临时事项',c:'#FF9500'}]
const semSwatches = [{n:'阻塞 / 逾期',c:'#FF3B30'},{n:'完成',c:'#34C759'},{n:'待更新',c:'#FFCC00'},{n:'AI',c:'#5856D6'},{n:'主题蓝',c:'#007AFF'}]
</script>
<style scoped>
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;align-items:start}
.panel{border-radius:var(--r-panel);padding:18px;display:flex;flex-direction:column;gap:10px}
.panel h3{margin:0;font-size:15px}
.sub{margin:0;font-size:12.5px;color:var(--text-2)}
.note{margin:0;font-size:13px;color:var(--text-2)}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
.demo-badge{display:inline-flex;align-items:center;gap:6px;font-size:12px;color:var(--text-2);padding:5px 10px;border-radius:999px;background:var(--field);border:1px solid var(--field-brd)}
.demo-badge i{width:7px;height:7px;border-radius:50%;background:var(--c-yellow);box-shadow:0 0 0 3px rgba(255,204,0,.25)}
.demo-card{position:relative;background:var(--card);border:1px solid rgba(255,255,255,.65);border-radius:var(--r-card);padding:14px 16px 14px 20px;overflow:hidden;box-shadow:0 4px 14px rgba(10,60,140,.08)}
.demo-card::before{content:"";position:absolute;left:0;top:0;bottom:0;width:4px;background:var(--c-defect)}
.dc-top{display:flex;justify-content:space-between;align-items:center}
.dc-title{font-size:15px;font-weight:600;margin:6px 0 8px}
.dc-meta{display:flex;gap:10px;align-items:center}
.due{font-size:12px;display:inline-flex;gap:4px;align-items:center}.due.over{color:var(--c-red);font-weight:700}
.meta-ico{display:inline-flex;gap:4px;align-items:center;font-size:12px;color:var(--text-2)}
.prio{font-size:12px;padding:2px 9px;border-radius:999px;font-weight:700}.prio.P0{color:#fff;background:var(--c-red)}.prio.P1{color:#B25E00;background:rgba(255,149,0,.18)}.prio.P2{color:#0A5CD6;background:rgba(0,122,255,.12)}.prio.P3{color:var(--text-2);background:var(--track)}
.tag{font-size:12px;padding:2px 9px;border-radius:999px;background:var(--sel);color:#0A5CD6;font-weight:500}
.rings{display:flex;gap:18px;justify-content:space-around;text-align:center}.rings p{margin:4px 0 0;font-size:12px;color:var(--text-2)}
.stack{display:inline-flex}.stk{margin-left:-8px;border:2px solid var(--card-solid)}.stk:first-child{margin-left:0}
.chk{display:inline-flex;gap:9px;align-items:center;font-size:13px}
.switch{position:relative;width:48px;height:28px;flex:none;display:inline-block}.switch input{position:absolute;opacity:0;width:100%;height:100%;cursor:pointer;margin:0}
.switch .tr{position:absolute;inset:0;border-radius:999px;background:var(--track);transition:background-color .2s;pointer-events:none}
.switch .tr::after{content:"";position:absolute;top:2px;left:2px;width:24px;height:24px;border-radius:50%;background:#fff;box-shadow:0 2px 6px rgba(10,40,90,.28);transition:transform .2s}
.switch input:checked + .tr{background:var(--c-green)}.switch input:checked + .tr::after{transform:translateX(20px)}
.swatches{display:flex;flex-direction:column;gap:8px}
.swatch{display:flex;align-items:center;gap:10px;border-radius:12px;border:1px solid rgba(255,255,255,.65);background:var(--card);padding:8px 12px}
.swatch i{width:34px;height:34px;border-radius:9px;flex:none}
.swatch b{display:block;font-size:13px}.swatch span{font-size:12px;color:var(--text-2)}
@media(max-width:1100px){.grid{grid-template-columns:repeat(2,1fr)}}
@media(max-width:700px){.grid{grid-template-columns:1fr}}
</style>
