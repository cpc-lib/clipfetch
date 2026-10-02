<script setup>
import { ref, computed, watch } from 'vue'
import { getDirectUrl, downloadViaServer, downloadSubtitleViaServer, errMsg } from '../api/video'
import { isCookieError } from '../api/cookies'
import { isLoggedIn, authModal } from '../stores/auth'
import { cookieModal } from '../stores/cookies'

const props = defineProps({
  info: { type: Object, required: true },
  url: { type: String, required: true }
})

const selectedId = ref('')
const downloading = ref(false)
const downloadingSubtitle = ref(false)
const statusText = ref('')
// 服务端下载实时进度（WebSocket 推送）
const progress = ref(null) // {percent, downloaded, total, speed}
// 选中的字幕语言代码（subtitles 数组已按中文优先排序，默认取第一个）
const selectedSubLang = ref('')

// 字幕语言代码 → 中文名（覆盖常见语言，未命中时显示原代码）
const SUB_LANG_NAMES = {
  'zh-Hans': '简体中文', 'zh-CN': '简体中文', 'zh-SG': '简体中文',
  'zh-Hant': '繁体中文', 'zh-TW': '繁體中文（台灣）', 'zh-HK': '繁體中文（香港）',
  zh: '中文', en: '英语', 'en-US': '英语（美国）', 'en-GB': '英语（英国）',
  ja: '日语', ko: '韩语', ar: '阿拉伯语', id: '印尼语', ms: '马来语',
  es: '西班牙语', th: '泰语', vi: '越南语', fr: '法语', de: '德语',
  ru: '俄语', pt: '葡萄牙语', hi: '印地语', tr: '土耳其语', it: '意大利语'
}
function subLangName(code) {
  return SUB_LANG_NAMES[code] || code
}

// 封面加载失败或无封面时的默认图（每次返回随机 Bing 每日壁纸）
const DEFAULT_THUMB = '/api/wallpaper'
function onThumbError(e) {
  if (!e.target.src.includes('/api/wallpaper')) e.target.src = DEFAULT_THUMB
}

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

// 轮播帖媒体明细（后端 media 字段）：视频栏 + 图片预览栏
const mediaItems = computed(() => props.info.media || [])
const hasCarousel = computed(() => mediaItems.value.length > 1)
const mediaVideos = computed(() => mediaItems.value.filter((m) => m.type === 'video'))
const mediaImages = computed(() => mediaItems.value.filter((m) => m.type === 'image'))
const lightbox = ref('')
// 正文（小红书笔记描述等）默认折叠，可展开全文
const descExpanded = ref(false)

// 轮播帖只有一种下载格式（ZIP 全部内容），自动选中并隐藏清晰度列表
watch(
  () => props.info,
  () => {
    if (hasCarousel.value && (props.info.formats || []).length === 1) {
      selectedId.value = props.info.formats[0].formatId
    }
    // 默认选中第一种字幕语言（数组已按中文优先排序）
    selectedSubLang.value = (props.info.subtitles || [])[0] || ''
    descExpanded.value = false
  },
  { immediate: true }
)

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

/**
 * 单独下载字幕文件（.vtt）
 */
async function downloadSubtitle() {
  if (downloadingSubtitle.value) return
  downloadingSubtitle.value = true
  statusText.value = ''
  try {
    const filename = await downloadSubtitleViaServer({
      url: props.url,
      title: props.info.title,
      lang: selectedSubLang.value
    })
    statusText.value = `字幕已保存：${filename}`
  } catch (e) {
    statusText.value = e.message || '字幕下载失败'
  } finally {
    downloadingSubtitle.value = false
  }
}
</script>

<template>
  <div class="flex h-full flex-col gap-5 rounded-card border border-slate-100 bg-white p-5 shadow-card sm:p-6">
    <!-- 缩略图 -->
    <div class="relative overflow-hidden rounded-card bg-slate-100">
      <img :src="info.thumbnail || DEFAULT_THUMB" :alt="info.title" class="aspect-video w-full object-cover" referrerpolicy="no-referrer" @error="onThumbError" />
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
      <!-- 笔记正文（小红书等） -->
      <template v-if="info.description">
        <p class="mt-2 whitespace-pre-wrap break-words text-sm leading-relaxed text-slate-500" :class="descExpanded ? '' : 'line-clamp-3'">{{ info.description }}</p>
        <button
          v-if="info.description.length > 60 || info.description.includes('\n')"
          class="mt-1 text-xs text-primary hover:underline"
          @click="descExpanded = !descExpanded"
        >{{ descExpanded ? '收起' : '展开全文' }}</button>
      </template>
    </div>

    <!-- 轮播帖预览：视频栏 + 图片栏 -->
    <div v-if="hasCarousel" class="flex-1">
      <div class="grid gap-4" :class="mediaVideos.length && mediaImages.length ? 'sm:grid-cols-2' : ''">
        <!-- 视频栏 -->
        <div v-if="mediaVideos.length">
          <p class="mb-2 text-sm font-medium text-slate-600">视频（{{ mediaVideos.length }}）</p>
          <div class="grid max-h-56 grid-cols-2 gap-2 overflow-y-auto pr-1">
            <div
              v-for="(m, i) in mediaVideos"
              :key="'v' + i"
              class="relative overflow-hidden rounded-xl border border-slate-200 bg-slate-100"
            >
              <img v-if="m.cover" :src="m.cover" class="aspect-video w-full object-cover" referrerpolicy="no-referrer" loading="lazy" />
              <div v-else class="flex aspect-video items-center justify-center text-xs text-slate-400">视频 {{ i + 1 }}</div>
              <span class="absolute bottom-1 left-1 rounded bg-black/60 px-1 text-[10px] text-white">视频 {{ i + 1 }}</span>
            </div>
          </div>
        </div>
        <!-- 图片栏 -->
        <div v-if="mediaImages.length">
          <p class="mb-2 text-sm font-medium text-slate-600">
            图片（{{ mediaImages.length }}）
          </p>
          <div class="grid max-h-56 grid-cols-3 gap-2 overflow-y-auto pr-1">
            <div
              v-for="(m, i) in mediaImages"
              :key="'i' + i"
              class="relative overflow-hidden rounded-xl border border-slate-200 bg-slate-100"
            >
              <img
                :src="m.url"
                class="aspect-square w-full cursor-zoom-in object-cover transition hover:opacity-90"
                referrerpolicy="no-referrer"
                loading="lazy"
                @click="lightbox = m.url"
              />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 格式选择 -->
    <div v-else class="flex-1">
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
      <!-- 字幕下载：语言选择 + 下载（仅当解析结果含字幕轨道时显示） -->
      <div v-if="info.hasSubtitles && (info.subtitles || []).length" class="mt-3">
        <p class="mb-2 text-sm font-medium text-slate-600">字幕语言 · {{ info.subtitles.length }} 种可选</p>
        <select
          v-model="selectedSubLang"
          class="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm text-slate-700 transition hover:border-primary/50 focus:border-primary focus:outline-none"
        >
          <option v-for="lang in info.subtitles" :key="lang" :value="lang">{{ subLangName(lang) }}</option>
        </select>
        <button
          class="btn-ghost mt-2 w-full py-3"
          :disabled="downloadingSubtitle || !selectedSubLang"
          @click="downloadSubtitle"
        >
          <svg v-if="downloadingSubtitle" class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
            <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
          </svg>
          <svg v-else viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 6h16M4 12h16M4 18h10" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          {{ downloadingSubtitle ? '字幕下载中…' : '下载所选语言字幕' }}
        </button>
      </div>
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

    <!-- 图片放大预览 -->
    <div
      v-if="lightbox"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-6"
      @click="lightbox = ''"
    >
      <img :src="lightbox" class="max-h-full max-w-full rounded-lg object-contain" referrerpolicy="no-referrer" />
    </div>
  </div>
</template>
