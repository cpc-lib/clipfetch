<template>
  <div class="min-h-screen flex flex-col bg-bg-main">
    <AppHeader />
    <main class="flex-1">
      <HeroSection
        @parse="handleParse"
        :loading="loading"
      />
      <VideoResult
        v-if="videoData"
        :video="videoData"
        :downloading="downloading"
        @download="handleDownload"
      />
      <FeatureSection />
      <PricingSection />
      <PlatformSection />
    </main>
    <AppFooter />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import AppHeader from './components/AppHeader.vue'
import HeroSection from './components/HeroSection.vue'
import VideoResult from './components/VideoResult.vue'
import FeatureSection from './components/FeatureSection.vue'
import PricingSection from './components/PricingSection.vue'
import PlatformSection from './components/PlatformSection.vue'
import AppFooter from './components/AppFooter.vue'
import { parseVideo, downloadViaServer } from './api/video.js'

const loading = ref(false)
const downloading = ref(false)
const videoData = ref(null)
const currentUrl = ref('')

async function handleParse(url) {
  loading.value = true
  videoData.value = null
  currentUrl.value = url
  try {
    const res = await parseVideo(url)
    if (res.success) {
      videoData.value = res.data
    } else {
      alert('解析失败：' + (res.error || '未知错误'))
    }
  } catch (err) {
    const msg = err.response?.data?.detail?.error || err.response?.data?.detail || err.message
    alert('解析失败：' + msg)
  } finally {
    loading.value = false
  }
}

async function handleDownload(formatId) {
  downloading.value = true
  try {
    const response = await downloadViaServer(currentUrl.value, formatId)
    const contentDisposition = response.headers['content-disposition']
    let filename = 'video.mp4'
    if (contentDisposition) {
      const match = contentDisposition.match(/filename\*?=(?:UTF-8'')?([^;\n]+)/i)
      if (match) filename = decodeURIComponent(match[1].replace(/"/g, ''))
    }
    const blob = new Blob([response.data])
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    window.URL.revokeObjectURL(url)
  } catch (err) {
    alert('下载失败：' + (err.message || '请稍后重试'))
  } finally {
    downloading.value = false
  }
}
</script>
