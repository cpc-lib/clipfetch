<script setup>
import { ref, computed } from 'vue'
import { getDirectUrl, downloadViaServer, errMsg } from '../api/video'
import { isCookieError } from '../api/cookies'
import { isLoggedIn, authModal } from '../stores/auth'
import { cookieModal } from '../stores/cookies'

const props = defineProps({
  info: { type: Object, required: true },
  url: { type: String, required: true }
})

const selectedId = ref('')
const downloading = ref(false)
const statusText = ref('')
// 服务端下载实时进度（WebSocket 推送）
const progress = ref(null) // {percent, downloaded, total, speed}

function fmtSize(n) {
  if (n == null || n < 0) return ''
  if (n >= 1073741824) return (n / 1073741824).toFixed(1) + ' GB'
  if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB'
  if (n >= 1024) return (n / 1024).toFixed(0) + ' KB'
  return n + ' B'
}

const videoFormats = computed(() => (props.info.formats || []).filter((f) => !f.audioOnly))
const audioFormats = computed(() => (props.info.formats || []).filter((f) => f.audioOnly))
const selected = computed(() => [...videoFormats.value, ...audioFormats.value].find((f) => f.formatId === selectedId.value))

function fmtDate(d) {
  if (!d || d.length !== 8) return ''
  return `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}`
}

function fmtCount(n) {
  if (n == null) return ''
  if (n >= 100000000) return (n / 100000000).toFixed(1) + '亿'
  if (n >= 10000) return (n / 10000).toFixed(1) + '万'
  return String(n)
}

/**
 * 下载策略：单流格式优先直链，失败/合并格式走服务端代理
 */
async function download() {
  if (!selectedId.value || downloading.value) return
  downloading.value = true
  statusText.value = ''
  const format = selected.value
  try {
    // serverOnly：YouTube/Twitter/Instagram 直链在被墙 CDN 上，必须走服务端代理
    if (!format.needsMerge && !format.serverOnly) {
      statusText.value = '获取直链中…'
      try {
        const { direct_url: directUrl } = await getDirectUrl(props.url, format.formatId)
        window.location.href = directUrl
        statusText.value = '已开始下载，若未保存请稍候重试或使用服务端下载'
        return
      } catch {
        // 直链不可用，回退代理模式
      }
    }
    statusText.value = format.needsMerge
      ? '服务端合并下载中，请稍候…'
      : format.formatId === 'images'
        ? '服务端下载/打包中，请稍候…'
        : '服务端下载中，请稍候…'
    progress.value = null
    const filename = await downloadViaServer({
      url: props.url,
      formatId: format.formatId,
      title: props.info.title,
      onProgress: (p) => { progress.value = p }
    })
    statusText.value = `已保存：${filename}`
  } catch (e) {
    statusText.value = e.message || '下载失败'
  } finally {
    downloading.value = false
    progress.value = null
  }
}
</script>

<template>
  <div class="flex h-full flex-col gap-5 rounded-card border border-slate-100 bg-white p-5 shadow-card sm:p-6">
    <!-- 缩略图 -->
    <div class="relative overflow-hidden rounded-card bg-slate-100">
      <img :src="info.thumbnail" :alt="info.title" class="aspect-video w-full object-cover" referrerpolicy="no-referrer" />
      <span v-if="info.durationString" class="absolute bottom-2 right-2 rounded-md bg-black/70 px-1.5 py-0.5 text-xs text-white">
        {{ info.durationString }}
      </span>
    </div>

    <!-- 元信息 -->
    <div>
      <h2 class="line-clamp-2 text-base font-semibold text-slate-800" :title="info.title">{{ info.title }}</h2>
      <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-400">
        <span class="rounded-full bg-primary-light px-2 py-0.5 font-medium text-primary">{{ info.platform }}</span>
        <span v-if="info.uploader">{{ info.uploader }}</span>
        <span v-if="info.viewCount != null">{{ fmtCount(info.viewCount) }} 次观看</span>
        <span v-if="info.uploadDate">{{ fmtDate(info.uploadDate) }}</span>
      </div>
    </div>

    <!-- 格式选择 -->
    <div class="flex-1">
      <p class="mb-2 text-sm font-medium text-slate-600">选择清晰度</p>
      <div class="grid max-h-56 grid-cols-1 gap-2 overflow-y-auto pr-1 sm:grid-cols-2">
        <label
          v-for="f in videoFormats"
          :key="f.formatId"
          class="flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2.5 text-sm transition"
          :class="selectedId === f.formatId ? 'border-primary bg-primary-light/50 text-primary-dark' : 'border-slate-200 text-slate-600 hover:border-primary/50'"
        >
          <input v-model="selectedId" type="radio" :value="f.formatId" class="accent-primary" />
          <span class="min-w-0 flex-1 truncate">{{ f.label }}</span>
          <span v-if="f.needsMerge" class="shrink-0 rounded bg-slate-100 px-1 text-[10px] text-slate-400">需合并</span>
        </label>
        <template v-if="audioFormats.length">
          <div class="col-span-full mt-1 text-xs text-slate-400">仅音频</div>
          <label
            v-for="f in audioFormats"
            :key="f.formatId"
            class="flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2.5 text-sm transition"
            :class="selectedId === f.formatId ? 'border-primary bg-primary-light/50 text-primary-dark' : 'border-slate-200 text-slate-600 hover:border-primary/50'"
          >
            <input v-model="selectedId" type="radio" :value="f.formatId" class="accent-primary" />
            <span class="min-w-0 flex-1 truncate">{{ f.label }}</span>
          </label>
        </template>
      </div>
    </div>

    <!-- 下载 -->
    <div>
      <button class="btn-primary w-full py-3" :disabled="!selectedId || downloading" @click="download">
        <svg v-if="downloading" class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
          <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
        </svg>
        <svg v-else viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M12 3v12m0 0l-4-4m4 4l4-4M4 21h16" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        {{ downloading ? '下载中…' : '下载视频' }}
      </button>
      <!-- 实时进度条（WebSocket 推送） -->
      <div v-if="downloading && progress" class="mt-3">
        <div class="h-2 w-full overflow-hidden rounded-full bg-slate-100">
          <div
            class="h-full rounded-full bg-primary transition-all duration-300"
            :style="{ width: (progress.percent ?? 0) + '%' }"
          ></div>
        </div>
        <p class="mt-1 text-center text-xs text-slate-500">
          <template v-if="progress.percent != null">{{ progress.percent }}% · </template>
          {{ fmtSize(progress.downloaded) }}<template v-if="progress.total > 0"> / {{ fmtSize(progress.total) }}</template>
          <template v-if="progress.speed > 0"> · {{ fmtSize(progress.speed) }}/s</template>
        </p>
      </div>
      <p v-if="statusText" class="mt-2 text-center text-xs" :class="statusText.includes('失败') || statusText.includes('已用完') || isCookieError(statusText) ? 'text-red-500' : 'text-emerald-600'">
        {{ statusText }}
        <button
          v-if="isCookieError(statusText)"
          class="ml-1 font-medium text-primary underline underline-offset-2 hover:opacity-80"
          @click="isLoggedIn ? cookieModal.open() : authModal.open('login')"
        >
          {{ isLoggedIn ? '去更新' : '去登录配置' }}
        </button>
      </p>
    </div>
  </div>
</template>
