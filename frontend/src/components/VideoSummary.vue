<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { marked } from 'marked'
import { Transformer } from 'markmap-lib'
import { Markmap } from 'markmap-view'
import { isLoggedIn, authModal } from '../stores/auth'
import { summarize, chat } from '../api/summarize'

const props = defineProps({
  url: { type: String, required: true },
  platform: { type: String, default: '' }
})

const activeTab = ref('summary')
const running = ref(false)
const errorText = ref('')

// ===== 总结 =====
const summaryMd = ref('')
const summaryDone = ref(false)
const summaryHtml = computed(() => marked(summaryMd.value))
const streaming = computed(() => running.value && !summaryDone.value)

// ===== 字幕 =====
const subtitleSegments = ref([])
const subtitleFullText = ref('')
const subtitleLang = ref('')
const subtitleExpanded = ref(false)

// ===== 思维导图 =====
const mindmapMd = ref('')
const mindmapDone = ref(false)
const mmContainer = ref(null)
let mmInstance = null
let transformer = new Transformer()

// ===== 问答 =====
const messages = ref([])
const questionInput = ref('')
const chatRunning = ref(false)
const chatBox = ref(null)

const tabs = [
  { key: 'summary', label: '总结摘要' },
  { key: 'subtitle', label: '字幕文本' },
  { key: 'mindmap', label: '思维导图' },
  { key: 'chat', label: 'AI 问答' }
]

onMounted(() => {
  if (isLoggedIn.value) {
    startSummarize()
  }
})

watch(isLoggedIn, (v) => {
  if (v && !summaryDone.value && !running.value && summaryMd.value === '') {
    startSummarize()
  }
})

function startSummarize() {
  if (running.value) return
  running.value = true
  errorText.value = ''
  summaryMd.value = ''
  summaryDone.value = false
  mindmapMd.value = ''
  mindmapDone.value = false
  subtitleSegments.value = []
  subtitleFullText.value = ''

  summarize(props.url, {
    onSubtitle(data) {
      subtitleSegments.value = data?.segments || []
      subtitleFullText.value = data?.fullText || ''
      subtitleLang.value = data?.language || ''
    },
    onToken(token) {
      summaryMd.value += token
    },
    onSummaryDone() {
      summaryDone.value = true
    },
    onMindmap(data) {
      mindmapMd.value = data?.markdown || ''
      mindmapDone.value = true
      nextTick(renderMindmap)
    },
    onDone() {
      running.value = false
      if (!summaryDone.value) summaryDone.value = true
    },
    onError(message) {
      running.value = false
      errorText.value = message
    },
    onUnauthorized() {
      running.value = false
      authModal.open('login')
    }
  })
}

// ===== 字幕下载 =====
function fmtSrtTime(sec) {
  const ms = Math.round((sec % 1) * 1000)
  const total = Math.floor(sec)
  const h = String(Math.floor(total / 3600)).padStart(2, '0')
  const m = String(Math.floor((total % 3600) / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${h}:${m}:${s},${String(ms).padStart(3, '0')}`
}

function buildSubtitleFile(type) {
  const segs = subtitleSegments.value
  if (!segs.length) return null
  if (type === 'txt') {
    return segs.map((s) => s.text).join('\n')
  }
  const sep = type === 'srt' ? ' --> ' : ' --> '
  return segs
    .map((s, i) => {
      const time = `${fmtSrtTime(s.start)} ${sep} ${fmtSrtTime(s.end)}`
      const body = type === 'vtt' ? s.text.replace(/\n/g, ' ') : s.text
      return type === 'srt' ? `${i + 1}\n${time}\n${body}\n` : `${time}\n${body}\n`
    })
    .join('\n')
}

function downloadSubtitle(type) {
  const content = buildSubtitleFile(type)
  if (!content) return
  const header = type === 'vtt' ? 'WEBVTT\n\n' : ''
  const blob = new Blob([header + content], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `subtitles.${type}`
  a.click()
  URL.revokeObjectURL(a.href)
}

// ===== 思维导图 =====
function renderMindmap() {
  if (!mmContainer.value || !mindmapMd.value) return
  const { root } = transformer.transform(mindmapMd.value)
  if (!mmInstance) {
    mmInstance = Markmap.create(mmContainer.value, {
      autoFit: true,
      duration: 300,
      spacingVertical: 8,
      initialExpandLevel: 3
    })
  }
  mmInstance.setData(root)
  mmInstance.fit()
}

function exportSvgNode() {
  const svg = mmContainer.value?.querySelector('svg')
  if (!svg) return null
  const clone = svg.cloneNode(true)
  const g = svg.querySelector('g')
  if (g) {
    const bbox = g.getBBox()
    const pad = 30
    clone.setAttribute('viewBox', `${bbox.x - pad} ${bbox.y - pad} ${bbox.width + pad * 2} ${bbox.height + pad * 2}`)
    clone.setAttribute('width', Math.ceil(bbox.width + pad * 2))
    clone.setAttribute('height', Math.ceil(bbox.height + pad * 2))
  }
  // foreignObject → text（规避 Canvas 跨域污染）
  clone.querySelectorAll('foreignObject').forEach((fo) => {
    const div = fo.querySelector('div')
    const text = document.createElementNS('http://www.w3.org/2000/svg', 'text')
    text.setAttribute('x', fo.getAttribute('x') || 0)
    text.setAttribute('y', parseFloat(fo.getAttribute('y') || 0) + 14)
    text.setAttribute('fill', div?.style.color || '#334155')
    text.setAttribute('font-size', div?.style.fontSize || '14px')
    text.setAttribute('font-family', 'system-ui, sans-serif')
    text.textContent = div?.textContent || ''
    fo.replaceWith(text)
  })
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  return clone
}

function exportMindmapSvg() {
  const svg = exportSvgNode()
  if (!svg) return
  const blob = new Blob([svg.outerHTML], { type: 'image/svg+xml;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'mindmap.svg'
  a.click()
  URL.revokeObjectURL(a.href)
}

function exportMindmapPng() {
  const svg = exportSvgNode()
  if (!svg) return
  const xml = new XMLSerializer().serializeToString(svg)
  const scale = 4
  const w = parseInt(svg.getAttribute('width') || 1200)
  const h = parseInt(svg.getAttribute('height') || 800)
  const img = new Image()
  img.onload = () => {
    const canvas = document.createElement('canvas')
    canvas.width = w * scale
    canvas.height = h * scale
    const ctx = canvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    canvas.toBlob((blob) => {
      if (!blob) return
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = 'mindmap.png'
      a.click()
      URL.revokeObjectURL(a.href)
    })
  }
  img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(xml)
}

function toggleFullscreen() {
  const el = mmContainer.value?.parentElement
  if (!el) return
  if (!document.fullscreenElement) {
    el.requestFullscreen?.().then(() => setTimeout(() => mmInstance?.fit(), 300))
  } else {
    document.exitFullscreen?.().then(() => setTimeout(() => mmInstance?.fit(), 300))
  }
}

// ===== AI 问答 =====
async function askQuestion() {
  const question = questionInput.value.trim()
  if (!question || chatRunning.value) return
  if (!isLoggedIn.value) {
    authModal.open('login')
    return
  }
  chatRunning.value = true
  questionInput.value = ''
  messages.value.push({ role: 'user', content: question })
  const answer = { role: 'assistant', content: '' }
  messages.value.push(answer)
  await nextTick()
  scrollChat()

  await chat(
    { url: props.url, question, subtitleText: subtitleFullText.value },
    {
      onToken(token) {
        answer.content += token
        scrollChat()
      },
      onDone() {
        chatRunning.value = false
      },
      onError(message) {
        chatRunning.value = false
        if (!answer.content) answer.content = `*${message}*`
      },
      onUnauthorized() {
        chatRunning.value = false
        authModal.open('login')
      }
    }
  )
}

function scrollChat() {
  nextTick(() => {
    chatBox.value?.scrollTo({ top: chatBox.value.scrollHeight })
  })
}

onBeforeUnmount(() => {
  mmInstance = null
})
</script>

<template>
  <div class="flex h-full flex-col rounded-card border border-slate-100 bg-white shadow-card">
    <!-- Tab 头 -->
    <div class="flex items-center justify-between border-b border-slate-100 px-4 pt-3 sm:px-6">
      <div class="flex gap-1">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="relative px-3 py-2.5 text-sm transition sm:px-4"
          :class="activeTab === t.key ? 'font-semibold text-primary' : 'text-slate-500 hover:text-slate-700'"
          @click="activeTab = t.key"
        >
          {{ t.label }}
          <span v-if="activeTab === t.key" class="absolute inset-x-3 bottom-0 h-0.5 rounded-full bg-primary sm:inset-x-4"></span>
        </button>
      </div>
      <button
        v-if="activeTab === 'summary' && !running"
        class="mb-1.5 text-xs text-slate-400 transition hover:text-primary"
        @click="startSummarize"
      >
        重新总结
      </button>
    </div>

    <!-- Tab 内容 -->
    <div class="flex-1 overflow-hidden p-4 sm:p-6">
      <!-- 未登录 -->
      <div v-if="!isLoggedIn" class="flex h-full flex-col items-center justify-center gap-3 text-center">
        <svg viewBox="0 0 24 24" class="h-10 w-10 text-slate-200" fill="none" stroke="currentColor" stroke-width="1.5">
          <rect x="4" y="10" width="16" height="10" rx="2" />
          <path d="M8 10V7a4 4 0 118 0v3" />
        </svg>
        <p class="text-sm text-slate-400">登录后即可免费使用 AI 视频总结（每天 3 次）</p>
        <button class="btn-primary" @click="authModal.open('login')">立即登录</button>
      </div>

      <template v-else>
        <!-- 总结摘要 -->
        <div v-show="activeTab === 'summary'" class="h-full overflow-y-auto pr-1">
          <div v-if="errorText" class="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-500">{{ errorText }}</div>
          <template v-else>
            <div v-if="!summaryMd && running" class="flex items-center gap-1.5 py-2 text-sm text-slate-400">
              正在分析视频字幕
              <span class="loading-dot inline-block h-1.5 w-1.5 rounded-full bg-primary"></span>
              <span class="loading-dot inline-block h-1.5 w-1.5 rounded-full bg-primary"></span>
              <span class="loading-dot inline-block h-1.5 w-1.5 rounded-full bg-primary"></span>
            </div>
            <div class="ai-prose text-sm leading-6 text-slate-600" v-html="summaryHtml"></div>
            <span v-if="streaming" class="typing-cursor ml-0.5 inline-block h-4 w-0.5 bg-primary align-middle"></span>
          </template>
        </div>

        <!-- 字幕文本 -->
        <div v-show="activeTab === 'subtitle'" class="flex h-full flex-col">
          <div v-if="!subtitleSegments.length" class="flex flex-1 items-center justify-center text-sm text-slate-400">
            {{ running ? '字幕获取中…' : '暂无字幕数据' }}
          </div>
          <template v-else>
            <div class="mb-3 flex items-center justify-between">
              <span class="text-xs text-slate-400">共 {{ subtitleSegments.length }} 段 · 语言 {{ subtitleLang }}</span>
              <div class="flex gap-2 text-xs">
                <button v-for="t in ['srt', 'vtt', 'txt']" :key="t" class="rounded-full border border-slate-200 px-2.5 py-1 text-slate-500 transition hover:border-primary hover:text-primary" @click="downloadSubtitle(t)">
                  {{ t.toUpperCase() }}
                </button>
              </div>
            </div>
            <div class="flex-1 space-y-1 overflow-y-auto rounded-xl bg-slate-50 p-3 text-sm">
              <div
                v-for="(s, i) in subtitleExpanded ? subtitleSegments : subtitleSegments.slice(0, 20)"
                :key="i"
                class="flex gap-3 rounded-lg px-2 py-1.5 transition hover:bg-white"
              >
                <span class="shrink-0 font-mono text-xs leading-6 text-primary/70">{{ s.startDisplay }}</span>
                <span class="leading-6 text-slate-600">{{ s.text }}</span>
              </div>
            </div>
            <button v-if="subtitleSegments.length > 20" class="mt-2 self-center text-xs text-primary hover:underline" @click="subtitleExpanded = !subtitleExpanded">
              {{ subtitleExpanded ? '收起' : `展开全部 ${subtitleSegments.length} 段` }}
            </button>
          </template>
        </div>

        <!-- 思维导图 -->
        <div v-show="activeTab === 'mindmap'" class="relative h-full">
          <div v-if="!mindmapDone" class="flex h-full items-center justify-center text-sm text-slate-400">
            {{ running ? '思维导图生成中…' : '暂无思维导图' }}
          </div>
          <template v-else>
            <div ref="mmContainer" class="h-full w-full"></div>
            <div class="absolute bottom-3 right-3 flex gap-2">
              <button class="btn-ghost !px-3 !py-1.5 text-xs" @click="toggleFullscreen">全屏</button>
              <button class="btn-ghost !px-3 !py-1.5 text-xs" @click="exportMindmapSvg">SVG</button>
              <button class="btn-ghost !px-3 !py-1.5 text-xs" @click="exportMindmapPng">PNG</button>
            </div>
          </template>
        </div>

        <!-- AI 问答 -->
        <div v-show="activeTab === 'chat'" class="flex h-full flex-col">
          <div ref="chatBox" class="flex-1 space-y-4 overflow-y-auto pr-1">
            <div v-if="!messages.length" class="flex h-full items-center justify-center text-center text-sm text-slate-400">
              基于视频字幕向 AI 提问<br />例如："这个视频的核心结论是什么？"
            </div>
            <div v-for="(m, i) in messages" :key="i" class="flex" :class="m.role === 'user' ? 'justify-end' : 'justify-start'">
              <div
                class="max-w-[85%] rounded-2xl px-4 py-2.5 text-sm"
                :class="m.role === 'user'
                  ? 'rounded-br-md bg-primary text-white'
                  : 'rounded-bl-md bg-slate-50 text-slate-600'"
              >
                <div v-if="m.role === 'assistant'" class="ai-prose" v-html="marked(m.content)"></div>
                <template v-else>{{ m.content }}</template>
                <span v-if="m.role === 'assistant' && chatRunning && i === messages.length - 1 && !m.content" class="typing-cursor inline-block h-3.5 w-0.5 bg-slate-400 align-middle"></span>
              </div>
            </div>
          </div>
          <div class="mt-3 flex items-center gap-2 rounded-full border border-slate-200 p-1.5 focus-within:border-primary">
            <input
              v-model="questionInput"
              type="text"
              class="min-w-0 flex-1 bg-transparent px-3 text-sm outline-none"
              placeholder="输入你的问题…"
              @keydown.enter="askQuestion"
            />
            <button class="btn-primary !px-5" :disabled="chatRunning || !questionInput.trim()" @click="askQuestion">发送</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
