<template>
  <!-- AI 入口统一星芒（1.1.0 品牌定稿 brand-ai-spark）：金色渐变 #FFE36E → #FF9F0A，深色下同色。
       几何与定稿 svg/brand-ai-spark.svg 一致（24 画布四角星芒），仅渐变起色按定稿口径 #FFE36E。 -->
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" aria-hidden="true" class="aispark">
    <defs><linearGradient id="ib-aispark-g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFE36E"/><stop offset="1" stop-color="#FF9F0A"/></linearGradient></defs>
    <path d="M12 1 Q13.76 10.24 23 12 Q13.76 13.76 12 23 Q10.24 13.76 1 12 Q10.24 10.24 12 1 Z" fill="url(#ib-aispark-g)"/>
  </svg>
</template>
<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 16 })
</script>
<style scoped>
.aispark{flex:none;display:inline-block;vertical-align:middle}
</style>
