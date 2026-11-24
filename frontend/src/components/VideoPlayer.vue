<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'

const props = defineProps({
  ticket: { type: String, required: true },
  mode: { type: String, required: true },
  mime: { type: String, default: '' },
  audioOnly: { type: Boolean, default: false },
  title: { type: String, default: '' },
  // 续播起点（秒），0 表示从头播放
  startAt: { type: Number, default: 0 }
})
const emit = defineEmits(['close', 'progress'])

const mediaEl = ref(null)
const errorText = ref('')
const loading = ref(true)
const resumeNotice = ref(false)
const resumedSeconds = ref(0)
let hls = null
let cctvLoader = null
// 进度上报节流：timeupdate 每 5 秒最多一次
let lastReportAt = 0
let startApplied = false
let hasLoaded = false
let pendingStartSeek = false

function fmtTime(sec) {
  const total = Math.max(0, Math.floor(sec || 0))
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function streamUrl() {
  return `/api/play/stream/${props.ticket}`
}

function fatalText(data) {
  if (props.mode === 'hls-decrypt' && data.response?.text) return data.response.text
  if (data.type === 'networkError') return '网络错误，播放地址可能已过期，请重新播放'
  if (data.type === 'mediaError') return '媒体解码失败，请尝试其他清晰度'
  return '播放失败，请重新播放'
}

function getStartAt() {
  const value = Number(props.startAt)
  return Number.isFinite(value) && value > 0 ? value : 0
}

function markResumed(seconds) {
  startApplied = true
  resumedSeconds.value = Math.floor(seconds)
  resumeNotice.value = true
  lastReportAt = Date.now()
}

// 非 hls.js（progressive / merge / 原生 HLS）续播跳转：在 loadedmetadata 拿到时长后执行一次
function applyStartAt() {
  if (startApplied || hls) return
  const el = mediaEl.value
  if (!el) return
  const startAt = getStartAt()
  if (startAt <= 0) {
    startApplied = true
    pendingStartSeek = false
    return
  }
  const duration = Number(el.duration)
  if (Number.isFinite(duration) && startAt >= duration - 3) {
    startApplied = true
    pendingStartSeek = false
    return
  }
  try {
    pendingStartSeek = true
    el.currentTime = startAt
    markResumed(startAt)
    if (!el.seeking && el.currentTime >= Math.max(1, startAt - 2)) {
      pendingStartSeek = false
    }
  } catch {
    pendingStartSeek = false
  }
}

function snapshot(completed = false) {
  const el = mediaEl.value
  if (!el) return null
  const duration = Number.isFinite(el.duration) ? Math.floor(el.duration) : null
  const position = Math.floor(el.currentTime || 0)
  return { position, duration, completed }
}

function canReportProgress() {
  return hasLoaded && !pendingStartSeek
}

function getCurrentProgress() {
  if (!canReportProgress()) return null
  const el = mediaEl.value
  if (!el) return null
  return snapshot(!!el.ended)
}

function report(completed = false, force = false) {
  const el = mediaEl.value
  if (!el) return
  if (completed) {
    const s = snapshot(true)
    if (s) {
      lastReportAt = Date.now()
      emit('progress', { ...s, position: s.duration ?? s.position, completed: true })
    }
    return
  }
  // 首帧未就绪或仍在执行初始续播跳转期间不上报，避免把 0 写回覆盖已有进度
  if (!canReportProgress() || el.seeking) return
  const now = Date.now()
  if (!force && now - lastReportAt < 5000) return
  lastReportAt = now
  const s = snapshot(false)
  if (s) emit('progress', s)
}

function playFromStart() {
  const el = mediaEl.value
  if (!el) return
  startApplied = true
  pendingStartSeek = false
  hasLoaded = true
  try {
    el.currentTime = 0
  } catch {
    /* ignore */
  }
  resumeNotice.value = false
  lastReportAt = Date.now()
  const s = snapshot(false)
  emit('progress', { position: 0, duration: s?.duration ?? null, completed: false })
  el.play?.().catch(() => {})
}

async function setup() {
  teardown()
  errorText.value = ''
  loading.value = true
  resumeNotice.value = false
  const startAt = getStartAt()
  startApplied = startAt <= 0
  hasLoaded = false
  pendingStartSeek = startAt > 0
  lastReportAt = Date.now()
  const el = mediaEl.value
  if (!el) return
  el.onloadeddata = () => {
    loading.value = false
    hasLoaded = true
    if (hls && startAt > 0 && !startApplied) {
      const duration = Number(el.duration)
      if (Number.isFinite(duration) && startAt >= duration - 3) {
        startApplied = true
        pendingStartSeek = false
      } else if (el.currentTime >= Math.max(1, startAt - 5)) {
        markResumed(startAt)
        pendingStartSeek = false
      } else if (!el.seeking) {
        try {
          el.currentTime = startAt
          markResumed(startAt)
          pendingStartSeek = el.seeking
        } catch {
          pendingStartSeek = false
        }
      }
    } else if (!pendingStartSeek && lastReportAt === 0) {
      lastReportAt = Date.now()
    }
    el.play().catch(() => {})
  }
  el.onloadedmetadata = applyStartAt
  el.onseeked = () => {
    if (pendingStartSeek) {
      if (startAt <= 0 || el.currentTime >= Math.max(1, startAt - 5)) {
        pendingStartSeek = false
        if (startAt > 0 && !startApplied) markResumed(startAt)
        lastReportAt = Date.now()
        if (el.readyState >= 2) {
          loading.value = false
          hasLoaded = true
          el.play().catch(() => {})
        }
      }
      return
    }
    if (hasLoaded) report(false, true)
  }
  el.ontimeupdate = () => report(false, false)
  el.onpause = () => {
    if (!el.ended) report(false, true)
  }
  el.onended = () => report(true, true)
  el.onerror = () => {
    if (hls) return
    loading.value = false
    errorText.value = '播放失败，链接可能已过期，请重新播放'
  }

  const decryptHls = props.mode === 'hls-decrypt'
  if (props.mode !== 'hls' && !decryptHls) {
    el.src = streamUrl()
    return
  }

  const url = `/api/play/hls/${props.ticket}/master.m3u8`
  // 加密分片必须经过 hls.js 自定义 loader，不能交给浏览器原生 HLS
  if (!decryptHls && el.canPlayType('application/vnd.apple.mpegurl')) {
    el.src = url
    return
  }
  try {
    const { default: Hls } = await import('hls.js')
    if (!Hls.isSupported()) {
      loading.value = false
      errorText.value = '当前浏览器不支持 HLS 播放'
      return
    }
    if (decryptHls) {
      const { createCctvHlsLoader } = await import('./cctvHlsLoader.js')
      cctvLoader = createCctvHlsLoader(Hls, props.ticket)
    }
    // 将续播起点直接交给 hls.js，避免在 MANIFEST_PARSED 过早 seek 被覆盖且跳过前面无关分片
    hls = new Hls({
      enableWorker: true,
      startPosition: startAt > 0 ? startAt : -1,
      fLoader: cctvLoader ? cctvLoader.Loader : undefined
    })
    hls.loadSource(url)
    hls.attachMedia(el)
    if (startAt > 0) {
      hls.once(Hls.Events.LEVEL_LOADED, (_event, data) => {
        const details = data?.details
        if (details && !details.live && Number.isFinite(details.totalduration)
          && startAt >= details.totalduration - 3) {
          startApplied = true
          pendingStartSeek = false
          hls?.startLoad(0)
        }
      })
    }
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data && data.fatal) {
        loading.value = false
        errorText.value = fatalText(data)
      }
    })
  } catch (e) {
    loading.value = false
    errorText.value = 'HLS 播放组件加载失败，请刷新后重试'
  }
}

function teardown() {
  const el = mediaEl.value
  if (hls) {
    hls.destroy()
    hls = null
  }
  if (cctvLoader) {
    cctvLoader.destroy()
    cctvLoader = null
  }
  if (el) {
    el.onloadeddata = null
    el.onloadedmetadata = null
    el.onseeked = null
    el.onerror = null
    el.ontimeupdate = null
    el.onpause = null
    el.onended = null
    try {
      el.pause()
    } catch {
      /* ignore */
    }
    el.removeAttribute('src')
    el.load()
  }
}

defineExpose({ getCurrentProgress })

watch(() => props.ticket, setup)
onMounted(setup)
onBeforeUnmount(() => {
  report(false, true)
  teardown()
})
</script>

<template>
  <div class="overflow-hidden rounded-xl border border-slate-700 bg-slate-900 p-3">
    <div class="mb-2 flex items-center justify-between gap-3">
      <p class="min-w-0 truncate text-sm font-medium text-slate-100">{{ title || '在线播放' }}</p>
      <button
        class="shrink-0 rounded-full bg-white/10 px-3 py-1 text-xs text-slate-100 transition hover:bg-white/20"
        @click="emit('close')"
      >关闭</button>
    </div>
    <video
      v-if="!audioOnly"
      ref="mediaEl"
      class="aspect-video w-full rounded-lg bg-black"
      controls
      autoplay
      playsinline
    ></video>
    <audio v-else ref="mediaEl" class="w-full" controls autoplay></audio>
    <div v-if="resumeNotice && !errorText" class="mt-2 flex items-center justify-center gap-2 text-xs text-slate-400">
      <span>已从 {{ fmtTime(resumedSeconds) }} 继续播放</span>
      <button class="font-medium text-primary hover:underline" @click="playFromStart">从头播放</button>
    </div>
    <p v-if="loading && !errorText" class="mt-2 text-center text-xs text-slate-400">
      {{ mode === 'hls-decrypt' ? '正在获取并解密首个分片…' : '加载中…' }}
    </p>
    <p v-if="errorText" class="mt-2 text-center text-xs text-red-300">{{ errorText }}</p>
  </div>
</template>
