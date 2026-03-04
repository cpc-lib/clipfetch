<template>
  <section class="py-6 sm:py-10 bg-white">
    <div class="max-w-4xl mx-auto px-4 sm:px-6">
      <div class="bg-white rounded-2xl border border-border shadow-lg overflow-hidden">
        <!-- 标签页导航 -->
        <div class="flex border-b border-border-light">
          <button
            v-for="tab in tabs"
            :key="tab.key"
            @click="activeTab = tab.key"
            :class="[
              'flex items-center gap-2 px-5 py-3.5 text-sm font-medium transition-all relative cursor-pointer',
              activeTab === tab.key
                ? 'text-primary'
                : 'text-text-secondary hover:text-text-primary'
            ]"
          >
            <span>{{ tab.icon }}</span>
            <span>{{ tab.label }}</span>
            <div
              v-if="activeTab === tab.key"
              class="absolute bottom-0 left-0 right-0 h-0.5 bg-primary"
            ></div>
          </button>
        </div>

        <!-- 内容区域 -->
        <div class="p-5 sm:p-6 min-h-[400px]">
          <!-- 加载状态 -->
          <div v-if="loading && !summaryText && activeTab === 'summary'" class="flex flex-col items-center justify-center py-16">
            <div class="w-12 h-12 border-4 border-primary/20 border-t-primary rounded-full animate-spin mb-4"></div>
            <p class="text-text-secondary text-sm">{{ loadingMessage }}</p>
          </div>

          <!-- 总结摘要 Tab -->
          <div v-show="activeTab === 'summary'">
            <div
              v-if="summaryText"
              class="prose prose-sm max-w-none text-text-primary leading-relaxed"
              v-html="renderedSummary"
            ></div>
            <div v-if="loading && summaryText" class="mt-2 inline-flex items-center gap-1.5 text-xs text-text-muted">
              <span class="w-1.5 h-1.5 bg-primary rounded-full animate-pulse"></span>
              AI 正在生成中...
            </div>
          </div>

          <!-- 字幕文本 Tab -->
          <div v-show="activeTab === 'subtitle'">
            <div v-if="subtitleData.segments && subtitleData.segments.length > 0">
              <div class="flex items-center justify-between mb-4">
                <div class="text-sm text-text-secondary">
                  共 {{ subtitleData.segments.length }} 条字幕
                  <span v-if="subtitleData.language" class="ml-2 px-2 py-0.5 bg-primary-light text-primary rounded-full text-xs">
                    {{ subtitleData.subtitle_type === 'manual' ? '人工字幕' : '自动字幕' }} · {{ subtitleData.language }}
                  </span>
                </div>
                <button
                  @click="subtitleExpanded = !subtitleExpanded"
                  class="text-xs text-primary hover:text-primary-dark transition-colors cursor-pointer"
                >
                  {{ subtitleExpanded ? '收起' : '展开全部' }}
                </button>
              </div>
              <div
                :class="['space-y-1 overflow-y-auto', subtitleExpanded ? 'max-h-none' : 'max-h-[500px]']"
              >
                <div
                  v-for="(seg, idx) in subtitleData.segments"
                  :key="idx"
                  class="flex gap-3 py-2 px-3 rounded-lg hover:bg-bg-section transition-colors group"
                >
                  <span class="flex-shrink-0 text-xs text-primary font-mono pt-0.5 min-w-[60px]">
                    {{ formatTime(seg.start) }}
                  </span>
                  <span class="text-sm text-text-primary leading-relaxed">{{ seg.text }}</span>
                </div>
              </div>
            </div>
            <div v-else-if="!loading" class="flex flex-col items-center justify-center py-16 text-text-muted">
              <svg class="w-12 h-12 mb-3 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
              </svg>
              <p class="text-sm">该视频暂无可用字幕</p>
            </div>
            <div v-else class="flex flex-col items-center justify-center py-16">
              <div class="w-10 h-10 border-4 border-primary/20 border-t-primary rounded-full animate-spin mb-3"></div>
              <p class="text-text-muted text-sm">正在提取字幕...</p>
            </div>
          </div>

          <!-- 思维导图 Tab -->
          <div v-show="activeTab === 'mindmap'">
            <div v-if="mindmapMarkdown" class="relative">
              <div ref="mindmapContainer" class="w-full min-h-[500px] border border-border-light rounded-xl bg-bg-section overflow-hidden">
                <svg ref="mindmapSvg" class="w-full h-full" style="min-height: 500px;"></svg>
              </div>
            </div>
            <div v-else-if="loading" class="flex flex-col items-center justify-center py-16">
              <div class="w-10 h-10 border-4 border-primary/20 border-t-primary rounded-full animate-spin mb-3"></div>
              <p class="text-text-muted text-sm">正在生成思维导图...</p>
            </div>
            <div v-else class="flex flex-col items-center justify-center py-16 text-text-muted">
              <svg class="w-12 h-12 mb-3 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2V6z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M14 6a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2V6z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2H6a2 2 0 01-2-2v-2z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M14 16a2 2 0 012-2h2a2 2 0 012 2v2a2 2 0 01-2 2h-2a2 2 0 01-2-2v-2z" />
              </svg>
              <p class="text-sm">请先生成总结以查看思维导图</p>
            </div>
          </div>

          <!-- AI 问答 Tab -->
          <div v-show="activeTab === 'qa'">
            <div class="space-y-4">
              <!-- 对话列表 -->
              <div
                ref="chatContainer"
                class="space-y-4 max-h-[400px] overflow-y-auto pr-1"
              >
                <div v-if="chatMessages.length === 0" class="flex flex-col items-center justify-center py-12 text-text-muted">
                  <svg class="w-12 h-12 mb-3 opacity-40" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
                  </svg>
                  <p class="text-sm mb-1">向 AI 提问关于这个视频的任何问题</p>
                  <p class="text-xs">例如："这个视频的核心观点是什么？"</p>
                </div>
                <div
                  v-for="(msg, idx) in chatMessages"
                  :key="idx"
                  :class="[
                    'flex',
                    msg.role === 'user' ? 'justify-end' : 'justify-start'
                  ]"
                >
                  <div
                    :class="[
                      'max-w-[80%] px-4 py-2.5 rounded-2xl text-sm leading-relaxed',
                      msg.role === 'user'
                        ? 'bg-primary text-white rounded-br-md'
                        : 'bg-bg-section text-text-primary rounded-bl-md border border-border-light'
                    ]"
                  >
                    <div v-if="msg.role === 'assistant'" v-html="renderMarkdown(msg.content)"></div>
                    <span v-else>{{ msg.content }}</span>
                    <span
                      v-if="msg.role === 'assistant' && msg.loading"
                      class="inline-block w-1.5 h-4 bg-primary/60 rounded-sm animate-pulse ml-0.5 align-text-bottom"
                    ></span>
                  </div>
                </div>
              </div>

              <!-- 输入区域 -->
              <div class="flex gap-2 pt-3 border-t border-border-light">
                <input
                  v-model="chatInput"
                  @keydown.enter.prevent="sendQuestion"
                  type="text"
                  placeholder="输入你的问题..."
                  class="flex-1 h-11 px-4 rounded-xl border border-border bg-white text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary transition-all"
                  :disabled="chatLoading"
                />
                <button
                  @click="sendQuestion"
                  :disabled="!chatInput.trim() || chatLoading"
                  class="h-11 px-5 rounded-xl bg-primary hover:bg-primary-dark text-white text-sm font-medium transition-all disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer flex items-center gap-1.5"
                >
                  <svg v-if="chatLoading" class="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                  <svg v-else class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 19l9 2-9-18-9 18 9-2zm0 0v-8" />
                  </svg>
                  发送
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { marked } from 'marked'
import { Transformer } from 'markmap-lib'
import { Markmap } from 'markmap-view'
import { summarizeVideo, chatWithVideo } from '../api/summarize.js'

const props = defineProps({
  videoUrl: { type: String, required: true },
  videoTitle: { type: String, default: '' },
})

const tabs = [
  { key: 'summary', label: '总结摘要', icon: '📝' },
  { key: 'subtitle', label: '字幕文本', icon: '📄' },
  { key: 'mindmap', label: '思维导图', icon: '🧠' },
  { key: 'qa', label: 'AI 问答', icon: '💬' },
]

const activeTab = ref('summary')
const loading = ref(false)
const loadingMessage = ref('正在提取视频字幕...')

const summaryText = ref('')
const subtitleData = ref({ segments: [], has_subtitle: false })
const subtitleExpanded = ref(false)
const mindmapMarkdown = ref('')
const mindmapSvg = ref(null)
const mindmapContainer = ref(null)

const chatMessages = ref([])
const chatInput = ref('')
const chatLoading = ref(false)
const chatContainer = ref(null)

const renderedSummary = ref('')

watch(summaryText, (val) => {
  renderedSummary.value = renderMarkdown(val)
})

watch(mindmapMarkdown, async (val) => {
  if (val) {
    await nextTick()
    renderMindmap(val)
  }
})

function renderMarkdown(text) {
  if (!text) return ''
  return marked.parse(text)
}

function renderMindmap(md) {
  if (!mindmapSvg.value) return
  try {
    mindmapSvg.value.innerHTML = ''
    const transformer = new Transformer()
    const { root } = transformer.transform(md)
    Markmap.create(mindmapSvg.value, { autoFit: true }, root)
  } catch (e) {
    console.warn('思维导图渲染失败:', e)
  }
}

function formatTime(seconds) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

async function startSummarize() {
  loading.value = true
  summaryText.value = ''
  mindmapMarkdown.value = ''
  loadingMessage.value = '正在提取视频字幕...'

  try {
    await summarizeVideo(props.videoUrl, 'zh', {
      subtitle: (data) => {
        try {
          subtitleData.value = JSON.parse(data)
          if (subtitleData.value.has_subtitle) {
            loadingMessage.value = 'AI 正在分析视频内容...'
          }
        } catch (e) { /* ignore parse error */ }
      },
      summary: (data) => {
        summaryText.value += data
      },
      mindmap: (data) => {
        try {
          const parsed = JSON.parse(data)
          mindmapMarkdown.value = parsed.markdown || ''
        } catch (e) { /* ignore parse error */ }
      },
      done: () => {
        loading.value = false
      },
      error: (data) => {
        loading.value = false
        try {
          const parsed = JSON.parse(data)
          alert(parsed.message || '总结失败')
        } catch (e) {
          alert('总结失败: ' + data)
        }
      },
    })
  } catch (err) {
    loading.value = false
    alert('总结请求失败: ' + err.message)
  }
}

async function sendQuestion() {
  const question = chatInput.value.trim()
  if (!question || chatLoading.value) return

  chatInput.value = ''
  chatMessages.value.push({ role: 'user', content: question })

  const aiMessage = { role: 'assistant', content: '', loading: true }
  chatMessages.value.push(aiMessage)
  chatLoading.value = true

  await nextTick()
  scrollChatToBottom()

  try {
    await chatWithVideo(
      props.videoUrl,
      question,
      subtitleData.value.full_text || '',
      {
        answer: (data) => {
          aiMessage.content += data
          scrollChatToBottom()
        },
        done: () => {
          aiMessage.loading = false
          chatLoading.value = false
        },
        error: (data) => {
          aiMessage.loading = false
          chatLoading.value = false
          try {
            const parsed = JSON.parse(data)
            aiMessage.content = '❌ ' + (parsed.message || '回答失败')
          } catch (e) {
            aiMessage.content = '❌ 回答失败'
          }
        },
      }
    )
  } catch (err) {
    aiMessage.loading = false
    chatLoading.value = false
    aiMessage.content = '❌ 请求失败: ' + err.message
  }
}

function scrollChatToBottom() {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

onMounted(() => {
  startSummarize()
})
</script>

<style scoped>
.prose :deep(h2) {
  font-size: 1.125rem;
  font-weight: 700;
  margin-top: 1.5rem;
  margin-bottom: 0.75rem;
  color: var(--color-text-primary);
  padding-bottom: 0.5rem;
  border-bottom: 1px solid var(--color-border-light);
}
.prose :deep(h3) {
  font-size: 1rem;
  font-weight: 600;
  margin-top: 1.25rem;
  margin-bottom: 0.5rem;
  color: var(--color-text-primary);
}
.prose :deep(p) {
  margin-bottom: 0.75rem;
  line-height: 1.75;
}
.prose :deep(ul), .prose :deep(ol) {
  margin-bottom: 0.75rem;
  padding-left: 1.5rem;
}
.prose :deep(li) {
  margin-bottom: 0.25rem;
  line-height: 1.75;
}
.prose :deep(strong) {
  color: var(--color-text-primary);
  font-weight: 600;
}
.prose :deep(hr) {
  margin: 1.25rem 0;
  border-color: var(--color-border-light);
}
</style>
