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
import { isLoggedIn, authModal } from './stores/auth'
import { cookieModal } from './stores/cookies'
import { appView } from './stores/app'
import { isCookieError } from './api/cookies'
import { parseVideo, errMsg } from './api/video'

const parsed = ref(null)
const currentUrl = ref('')
const loading = ref(false)
const errorText = ref('')
const resultSection = ref(null)

const compact = computed(() => !!parsed.value)

async function handleParse(url) {
  loading.value = true
  errorText.value = ''
  parsed.value = null
  currentUrl.value = url
  try {
    parsed.value = await parseVideo(url)
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
          {{ isLoggedIn ? '去更新 Cookies' : '登录并配置 Cookies' }}
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

      <PlatformSection />
    </template>

    <AppFooter />
    <AuthModal />
    <CookieModal />
  </div>
</template>
