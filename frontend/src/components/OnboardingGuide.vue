<template>
<Modal :open="open" @close="finish">
  <p class="tiny step-n">新手引导 · 第 {{step + 1}} / 3 步（可随时跳过）</p>
  <h2 style="margin:6px 0 8px">{{steps[step].title}}</h2>
  <p class="small" style="line-height:1.7">{{steps[step].body}}</p>
  <div class="dots"><span v-for="(_, i) in steps" :key="i" class="dot" :class="{on: i===step}"/></div>
  <div class="foot">
    <AppButton variant="ghost" @click="finish">跳过引导</AppButton><span style="flex:1"/>
    <AppButton v-if="step < 2" @click="step++">下一步</AppButton>
    <AppButton v-else @click="finish">知道了，开始使用</AppButton>
  </div>
</Modal>
</template>
<script setup lang="ts">
// 新手引导（修订三五.1）：成员第一次进入某小组时显示三步引导，看过/跳过后按「用户+小组」记 localStorage，不再弹。
import { ref } from 'vue'
import Modal from './Modal.vue'; import AppButton from './AppButton.vue'
const props = defineProps<{ open: boolean; userId: number; teamId: number }>()
const emit = defineEmits(['close'])
const step = ref(0)
const steps = [
  { title: '认领池：先从这里领活', body: '左侧「认领池」里是还没人负责的事项。看到自己能做的，点进事项点「认领」就会进入你的工作台；被邀请认领的事项会出现在工作台右侧「邀请我认领」里。' },
  { title: '登记进展：每天顺手更新', body: '在工作台或事项详情里登记进展：写一句话、拉一下进度，卡住了就勾选「阻塞」并写清原因。组长和 PO 在站会、总览里看的就是这些更新，不用另写日报。' },
  { title: '提意见反馈：用着不顺手就说', body: '侧栏底部有「意见反馈」，任何页面都能打开：选类型、写标题和描述，还能直接粘贴截图。别人的反馈可以 +1 和评论，避免重复提交。' },
]
import { markOnboardingSeen } from '../utils/onboarding'
function finish() { markOnboardingSeen(props.userId, props.teamId); emit('close') }
</script>
<style scoped>
.step-n{color:var(--text-3);font-weight:600}
.dots{display:flex;gap:6px;margin:16px 0 4px}.dot{width:8px;height:8px;border-radius:50%;background:var(--track)}.dot.on{background:var(--theme)}
.foot{display:flex;gap:10px;align-items:center;margin-top:14px}
</style>
