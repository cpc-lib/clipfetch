<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import AppHeader from './components/AppHeader.vue'
import HeroSection from './components/HeroSection.vue'
import VideoResult from './components/VideoResult.vue'
import PlatformSection from './components/PlatformSection.vue'
import AppFooter from './components/AppFooter.vue'
import AuthModal from './components/AuthModal.vue'
import CookieModal from './components/CookieModal.vue'
import SubtitleStudio from './components/SubtitleStudio.vue'
import MusicLibrary from './components/MusicLibrary.vue'
import PreParseLibrary from './components/PreParseLibrary.vue'
import { isLoggedIn, authModal } from './stores/auth'
import { cookieModal } from './stores/cookies'
import { appView } from './stores/app'
import { isCookieError } from './api/cookies'
import { parseVideo, errMsg } from './api/video'
import { markParsed } from './api/preparse'

const parsed = ref(null)
const currentUrl = ref('')
const loading = ref(false)
const errorText = ref('')
const resultSection = ref(null)
// 视频下载页内嵌文件库面板（每次展开重新挂载，自动刷新下载状态）
const showLibrary = ref(false)

const compact = computed(() => !!parsed.value)

async function handleParse(url) {
  loading.value = true
  errorText.value = ''
  parsed.value = null
  currentUrl.value = url
  try {
    parsed.value = await parseVideo(url)
    // 解析成功后异步标记预解析库中该 URL 为已解析（不阻塞主流程）
    markParsed(url).catch(() => {})
    await nextTick()
    resultSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } catch (e) {
    errorText.value = e.message || errMsg(e)
  } finally {
    loading.value = false
  }
}

// 从文件库跳转到视频解析页时，清空上次解析结果和错误提示
watch(() => appView.prefillUrl, (url) => {
  if (url) {
    parsed.value = null
    errorText.value = ''
  }
})
</script>

<template>
  <div class="min-h-screen">
    <AppHeader />

    <!-- 字幕转换视图 -->
    <SubtitleStudio v-if="appView.active === 'subtitle'" />

    <!-- 网易云音乐文件库视图 -->
    <MusicLibrary v-else-if="appView.active === 'library'" />

    <!-- 预解析库视图 -->
    <PreParseLibrary v-else-if="appView.active === 'preparse'" />

    <!-- 视频下载视图（默认） -->
    <template v-else>
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
    </template>

    <AppFooter />
    <AuthModal />
    <CookieModal />
  </div>
</template>
