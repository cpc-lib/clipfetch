<script setup>
import { ref, computed, nextTick } from 'vue'
import AppHeader from './components/AppHeader.vue'
import HeroSection from './components/HeroSection.vue'
import VideoResult from './components/VideoResult.vue'
import VideoSummary from './components/VideoSummary.vue'
import FeatureSection from './components/FeatureSection.vue'
import PricingSection from './components/PricingSection.vue'
import PlatformSection from './components/PlatformSection.vue'
import AppFooter from './components/AppFooter.vue'
import AuthModal from './components/AuthModal.vue'
import CookieModal from './components/CookieModal.vue'
import { isLoggedIn, authModal } from './stores/auth'
import { cookieModal } from './stores/cookies'
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
</script>

<template>
  <div class="min-h-screen bg-white">
    <AppHeader />
    <HeroSection :loading="loading" :compact="compact" @parse="handleParse" />

    <p v-if="errorText" class="mx-auto -mt-2 mb-4 max-w-2xl px-4 text-center text-sm text-red-500">
      {{ errorText }}
      <button
        v-if="isCookieError(errorText)"
        class="ml-1 font-medium text-primary underline underline-offset-2 hover:opacity-80"
        @click="isLoggedIn ? cookieModal.open() : authModal.open('login')"
      >
        {{ isLoggedIn ? '去更新 Cookies' : '登录并配置 Cookies' }}
      </button>
    </p>

    <!-- 解析结果：左右双栏（移动端上下堆叠） -->
    <section v-if="parsed" ref="resultSection" class="mx-auto max-w-7xl scroll-mt-20 px-4 pb-10 sm:px-6">
      <Transition name="fade" appear>
        <div class="grid grid-cols-1 gap-5 lg:grid-cols-5">
          <div class="lg:col-span-2">
            <VideoResult :info="parsed" :url="currentUrl" />
          </div>
          <div class="min-h-[560px] lg:col-span-3">
            <VideoSummary :url="currentUrl" :platform="parsed.platform" />
          </div>
        </div>
      </Transition>
    </section>

    <FeatureSection />
    <PricingSection />
    <PlatformSection />
    <AppFooter />
    <AuthModal />
    <CookieModal />
  </div>
</template>
