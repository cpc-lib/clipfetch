<script setup>
import { ref, computed, nextTick, watch, defineAsyncComponent } from 'vue'
import HeroSection from '../components/HeroSection.vue'
import VideoResult from '../components/VideoResult.vue'
import PlatformSection from '../components/PlatformSection.vue'
import { isLoggedIn, authModal } from '../stores/auth'
import { cookieModal } from '../stores/cookies'
import { appView } from '../stores/app'
import { isCookieError } from '../api/cookies'
import { parseVideo, errMsg } from '../api/video'
import { markParsed } from '../api/preparse'

// 文件库面板异步按需加载，未展开时不占用首屏加载体积
const MusicLibrary = defineAsyncComponent(() => import('../components/MusicLibrary.vue'))

const parsed = ref(null)
const currentUrl = ref('')
const loading = ref(false)
const errorText = ref('')
const resultSection = ref(null)
const showLibrary = ref(false)

const compact = computed(() => !!parsed.value)

async function handleParse(url) {
  loading.value = true
  errorText.value = ''
  parsed.value = null
  currentUrl.value = url
  try {
    parsed.value = await parseVideo(url)
    markParsed(url).catch(() => {})
    await nextTick()
    resultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (e) {
    errorText.value = e.message || errMsg(e)
  } finally {
    loading.value = false
  }
}

watch(() => appView.prefillUrl, (url) => {
  if (url) {
    parsed.value = null
    errorText.value = ''
  }
})
</script>

<template>
  <div>
    <HeroSection :loading="loading" :compact="compact" @parse="handleParse" />

    <p v-if="errorText" class="mx-auto mt-4 mb-4 max-w-2xl px-4 text-center text-sm text-red-500">
      {{ errorText }}
      <button
        v-if="isCookieError(errorText)"
        class="ml-1 font-medium text-primary underline underline-offset-2 hover:opacity-80"
        @click="isLoggedIn ? cookieModal.open() : authModal.open('login')"
      >
        {{ isLoggedIn ? '去处理' : '登录并配置' }}
      </button>
    </p>

    <!-- 解析结果 -->
    <section v-if="parsed" ref="resultSection" class="mx-auto max-w-7xl scroll-mt-20 px-4 pb-10 sm:px-6">
      <Transition name="fade" appear>
        <div class="mx-auto max-w-xl">
          <VideoResult :info="parsed" :url="currentUrl" />
        </div>
      </Transition>
    </section>

    <!-- 内嵌文件库：无需切换到"文件库"标签即可选歌解析 -->
    <section class="mx-auto max-w-7xl scroll-mt-20 px-4 pb-6 sm:px-6">
      <div class="overflow-hidden rounded-card border border-slate-100 bg-white shadow-card">
        <button
          class="flex w-full items-center justify-between px-5 py-4 text-left transition hover:bg-slate-50/60"
          @click="showLibrary = !showLibrary"
        >
          <span class="text-base font-semibold text-slate-800">文件库</span>
          <span class="text-sm text-slate-400">{{ showLibrary ? '收起' : '展开' }}</span>
        </button>
        <div v-if="showLibrary" class="border-t border-slate-100">
          <MusicLibrary embedded />
        </div>
      </div>
    </section>

    <PlatformSection />
  </div>
</template>
