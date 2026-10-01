<script setup>
import { ref } from 'vue'

const props = defineProps({
  loading: Boolean,
  compact: Boolean
})
const emit = defineEmits(['parse'])
const url = ref('')

function submit() {
  if (!url.value.trim() || props.loading) return
  emit('parse', url.value.trim())
}
</script>

<template>
  <section class="relative overflow-hidden bg-gradient-to-br from-[#FCE7F3] via-[#F3E8FF]/70 to-white">
    <div class="mx-auto max-w-7xl px-4 pb-8 pt-14 text-center sm:px-6 sm:pb-12" :class="compact ? 'sm:pt-8' : 'sm:pt-16'">
      <template v-if="!compact">
        <h1 class="mx-auto max-w-3xl text-3xl font-bold leading-tight text-slate-900 sm:text-5xl">
          一个链接，下载全网视频
        </h1>
        <p class="mx-auto mt-4 max-w-2xl text-base text-slate-500 sm:text-lg">
          支持 YouTube / 抖音 / Twitter 等主流平台，多种清晰度自由选择，还可 AI 一键总结长视频
        </p>
      </template>

      <div class="mx-auto mt-8 max-w-2xl" :class="compact && 'mt-0 max-w-3xl'">
        <div class="flex items-center gap-2 rounded-full border-2 border-slate-200 bg-white p-1.5 shadow-card transition focus-within:border-primary sm:p-2">
          <svg viewBox="0 0 24 24" class="ml-2 hidden h-5 w-5 shrink-0 text-slate-300 sm:block" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" stroke-linecap="round" />
          </svg>
          <input
            v-model="url"
            type="text"
            class="min-w-0 flex-1 bg-transparent px-2 text-sm outline-none placeholder:text-slate-300 sm:text-base"
            placeholder="粘贴视频链接，如 https://www.youtube.com/watch?v=..."
            @keydown.enter="submit"
          />
          <button class="btn-primary shrink-0 px-5 sm:px-8" :disabled="loading" @click="submit">
            <svg v-if="loading" class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
              <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
              <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
            </svg>
            <span>{{ loading ? '解析中' : '开始解析' }}</span>
          </button>
        </div>
        <p v-if="!compact" class="mt-3 text-xs text-slate-400">
          支持视频解析下载与 AI 总结 · 无需安装客户端 · 免费使用
        </p>
      </div>
    </div>
  </section>
</template>
