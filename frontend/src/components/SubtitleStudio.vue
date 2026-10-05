<script setup>
import { ref, watch, onMounted } from 'vue'
import { isLoggedIn, authModal } from '../stores/auth'
import {
  getSubtitleSession,
  connectSubtitle,
  disconnectSubtitle,
  listSubtitles,
  listLangs,
  getSubtitle,
  uploadSubtitle,
  translateSubtitle,
  saveSubtitle,
  deleteSubtitle,
  downloadSubtitle
} from '../api/subtitles'
import { errMsg } from '../api/request'

const langs = ref([])
const history = ref([])
const current = ref(null)
const targetLang = ref('')
const selectedFile = ref(null)
const fileInput = ref(null)

const loading = ref(false)
const uploading = ref(false)
const translating = ref(false)
const saving = ref(false)
const status = ref({ type: '', text: '' })

// 第三方字幕服务连接状态：null=检查中 / true / false
const connected = ref(null)
const connecting = ref(false)
const connectForm = ref({ username: '', password: '', tenantCode: '' })
const showPassword = ref(false)

function notify(type, text) {
  status.value = { type, text }
}

/** 428 = 未连接或第三方会话过期，自动回到连接表单 */
function handleApiError(e) {
  if (e?.response?.status === 428) {
    connected.value = false
    current.value = null
    history.value = []
  }
  notify('error', errMsg(e))
}

async function init() {
  loading.value = true
  notify('', '')
  try {
    const session = await getSubtitleSession()
    connected.value = !!session.connected
    if (connected.value) await loadData()
  } catch (e) {
    connected.value = false
    notify('error', errMsg(e))
  } finally {
    loading.value = false
  }
}

async function loadData() {
  const [langList, historyList] = await Promise.all([listLangs(), listSubtitles()])
  langs.value = langList || []
  history.value = historyList || []
  if (!targetLang.value && langs.value.length) {
    targetLang.value = langs.value[0].name
  }
}

async function doConnect() {
  if (!connectForm.value.username || !connectForm.value.password || !connectForm.value.tenantCode) {
    notify('error', '请完整填写用户名、密码和租户编码')
    return
  }
  connecting.value = true
  notify('', '')
  try {
    await connectSubtitle({ ...connectForm.value })
    connected.value = true
    await loadData()
    notify('success', '字幕服务连接成功')
  } catch (e) {
    notify('error', errMsg(e))
  } finally {
    connecting.value = false
  }
}

async function doDisconnect() {
  if (!window.confirm('确定断开字幕服务连接吗？')) return
  try {
    await disconnectSubtitle()
    connected.value = false
    current.value = null
    history.value = []
    langs.value = []
    connectForm.value.password = ''
    notify('', '')
  } catch (e) {
    notify('error', errMsg(e))
  }
}

function onFileChange(e) {
  selectedFile.value = e.target.files?.[0] || null
}

async function doUpload() {
  if (!selectedFile.value) {
    notify('error', '请先选择字幕文件')
    return
  }
  uploading.value = true
  notify('', '')
  try {
    current.value = await uploadSubtitle(selectedFile.value)
    notify('success', `上传成功，共 ${current.value.cues?.length || 0} 条字幕`)
    selectedFile.value = null
    if (fileInput.value) fileInput.value.value = ''
    await refreshHistory()
  } catch (e) {
    handleApiError(e)
  } finally {
    uploading.value = false
  }
}

async function openHistory(item) {
  loading.value = true
  notify('', '')
  try {
    current.value = await getSubtitle(item.id)
    if (current.value.targetLang) {
      targetLang.value = current.value.targetLang
    }
  } catch (e) {
    handleApiError(e)
  } finally {
    loading.value = false
  }
}

async function doTranslate() {
  if (!current.value || !targetLang.value) return
  translating.value = true
  notify('', '')
  try {
    current.value = await translateSubtitle(current.value.id, targetLang.value)
    notify('success', '翻译完成')
    await refreshHistory()
  } catch (e) {
    handleApiError(e)
  } finally {
    translating.value = false
  }
}

async function doSave() {
  if (!current.value) return
  saving.value = true
  notify('', '')
  try {
    current.value = await saveSubtitle(current.value.id, current.value.cues)
    notify('success', '已保存')
    await refreshHistory()
  } catch (e) {
    handleApiError(e)
  } finally {
    saving.value = false
  }
}

async function doDownload() {
  if (!current.value) return
  try {
    await downloadSubtitle(current.value.id)
  } catch (e) {
    handleApiError(e)
  }
}

async function doDelete() {
  if (!current.value) return
  if (!window.confirm(`确定删除「${current.value.originalName}」吗？`)) return
  try {
    await deleteSubtitle(current.value.id)
    notify('success', '已删除')
    current.value = null
    await refreshHistory()
  } catch (e) {
    handleApiError(e)
  }
}

async function refreshHistory() {
  try {
    history.value = (await listSubtitles()) || []
  } catch (e) {
    if (e?.response?.status === 428) {
      connected.value = false
    }
    // 历史刷新失败不打扰主流程
  }
}

function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

onMounted(() => {
  if (isLoggedIn.value) init()
})
watch(isLoggedIn, (v) => {
  if (v) init()
})
</script>

<template>
  <section class="mx-auto max-w-7xl px-4 pb-16 pt-10 sm:px-6">
    <h1 class="text-2xl font-bold text-slate-900 sm:text-3xl">字幕转换</h1>
    <p class="mt-2 text-sm text-slate-500">
      上传 SRT / VTT / ASS 字幕文件，翻译为目标语言，可在线校对后下载 SRT
    </p>

    <!-- 未登录提示 -->
    <div v-if="!isLoggedIn" class="mt-10 rounded-card border border-slate-100 bg-white p-10 text-center shadow-card">
      <div class="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary-light">
        <svg viewBox="0 0 24 24" class="h-7 w-7 text-primary" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="11" width="18" height="10" rx="2" />
          <path d="M7 11V7a5 5 0 0110 0v4" stroke-linecap="round" />
        </svg>
      </div>
      <h2 class="text-base font-semibold text-slate-800">登录后使用字幕转换</h2>
      <p class="mt-1 text-sm text-slate-400">字幕服务需要校验 ClipFetch 登录态</p>
      <button class="btn-primary mt-5" @click="authModal.open('login')">立即登录</button>
    </div>

    <!-- 检查连接状态中 -->
    <div v-else-if="connected === null" class="mt-10 text-center text-sm text-slate-400">
      正在检查连接状态…
    </div>

    <!-- 第三方账号连接表单 -->
    <div v-else-if="!connected" class="mt-8 mx-auto max-w-md rounded-card border border-slate-100 bg-white p-7 shadow-card">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-full bg-primary-light">
          <svg viewBox="0 0 24 24" class="h-5 w-5 text-primary" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M15 3h4a2 2 0 012 2v14a2 2 0 01-2 2h-4" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M10 17l5-5-5-5M15 12H3" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <div>
          <h2 class="text-sm font-semibold text-slate-800">连接字幕服务账号</h2>
          <p class="mt-0.5 text-xs text-slate-400">使用你自己的第三方平台账号，凭证仅用于本次登录，不会保存密码</p>
        </div>
      </div>

      <div class="mt-5 space-y-4">
        <div>
          <label class="mb-1.5 block text-xs font-medium text-slate-600">用户名</label>
          <input
            v-model="connectForm.username"
            type="text"
            autocomplete="username"
            placeholder="请输入第三方账号用户名"
            class="w-full rounded-xl border border-slate-200 px-4 py-2.5 text-sm text-slate-700 outline-none transition focus:border-primary"
          >
        </div>
        <div>
          <label class="mb-1.5 block text-xs font-medium text-slate-600">密码</label>
          <div class="relative">
            <input
              v-model="connectForm.password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              class="w-full rounded-xl border border-slate-200 px-4 py-2.5 pr-11 text-sm text-slate-700 outline-none transition focus:border-primary"
            >
            <button
              type="button"
              class="absolute inset-y-0 right-0 flex w-10 items-center justify-center text-slate-400 transition hover:text-primary"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <svg v-if="showPassword" viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" stroke-linejoin="round" />
                <circle cx="12" cy="12" r="3" />
              </svg>
              <svg v-else viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M17.94 17.94A10.07 10.07 0 0112 19c-6.5 0-10-7-10-7a18.45 18.45 0 015.06-5.94M9.9 4.24A9.12 9.12 0 0112 4c6.5 0 10 7 10 7a18.5 18.5 0 01-2.16 3.19M1 1l22 22M9.5 9.5a3 3 0 104.2 4.2" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>
        </div>
        <div>
          <label class="mb-1.5 block text-xs font-medium text-slate-600">租户编码</label>
          <input
            v-model="connectForm.tenantCode"
            type="text"
            placeholder="如 TENANT705879"
            class="w-full rounded-xl border border-slate-200 px-4 py-2.5 text-sm uppercase tracking-wide text-slate-700 outline-none transition focus:border-primary"
          >
        </div>
      </div>

      <p
        v-if="status.type === 'error'"
        class="mt-4 rounded-lg bg-red-50 px-3 py-2 text-xs text-red-600"
      >
        {{ status.text }}
      </p>

      <button class="btn-primary mt-5 w-full" :disabled="connecting" @click="doConnect">
        <svg v-if="connecting" class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
          <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
          <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
        </svg>
        {{ connecting ? '连接中…' : '连接' }}
      </button>
    </div>

    <template v-else>
      <!-- 连接状态条 -->
      <div class="mt-5 flex items-center justify-between rounded-xl border border-emerald-100 bg-emerald-50/60 px-4 py-2.5">
        <div class="flex items-center gap-2 text-xs text-emerald-700">
          <span class="relative flex h-2 w-2">
            <span class="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-60"></span>
            <span class="relative inline-flex h-2 w-2 rounded-full bg-emerald-500"></span>
          </span>
          已连接字幕服务
        </div>
        <button class="text-xs text-slate-400 transition hover:text-red-500" @click="doDisconnect">断开连接</button>
      </div>

      <!-- 状态提示 -->
      <p
        v-if="status.text"
        class="mt-4 rounded-lg px-4 py-2 text-sm"
        :class="status.type === 'error'
          ? 'bg-red-50 text-red-600'
          : 'bg-emerald-50 text-emerald-600'"
      >
        {{ status.text }}
      </p>

      <div class="mt-6 grid gap-6 lg:grid-cols-[320px_1fr]">
        <!-- 左栏：上传 + 历史 -->
        <div class="space-y-6">
          <div class="rounded-card border border-slate-100 bg-white p-5 shadow-card">
            <h3 class="text-sm font-semibold text-slate-800">上传字幕</h3>
            <label
              class="mt-3 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-200 px-4 py-8 text-center transition hover:border-primary"
            >
              <svg viewBox="0 0 24 24" class="h-8 w-8 text-slate-300" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M12 16V4m0 0l-4 4m4-4l4 4" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M4 16v2a2 2 0 002 2h12a2 2 0 002-2v-2" stroke-linecap="round" />
              </svg>
              <span class="text-xs text-slate-400">点击选择 .srt / .vtt / .ass 文件</span>
              <span class="max-w-full break-all text-xs font-medium text-slate-600">{{ selectedFile?.name || '未选择文件' }}</span>
              <input
                ref="fileInput"
                type="file"
                accept=".srt,.vtt,.ass"
                class="hidden"
                @change="onFileChange"
              />
            </label>
            <button class="btn-primary mt-4 w-full" :disabled="uploading || !selectedFile" @click="doUpload">
              <svg v-if="uploading" class="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
                <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
              </svg>
              {{ uploading ? '上传中' : '上传并解析' }}
            </button>
          </div>

          <div class="rounded-card border border-slate-100 bg-white p-5 shadow-card">
            <h3 class="text-sm font-semibold text-slate-800">历史记录</h3>
            <div v-if="loading && !history.length" class="py-6 text-center text-xs text-slate-400">加载中…</div>
            <div v-else-if="!history.length" class="py-6 text-center text-xs text-slate-400">暂无字幕记录</div>
            <ul v-else class="mt-3 space-y-1">
              <li v-for="item in history" :key="item.id">
                <button
                  class="w-full rounded-lg px-3 py-2 text-left transition"
                  :class="current?.id === item.id ? 'bg-primary-light/60' : 'hover:bg-slate-50'"
                  @click="openHistory(item)"
                >
                  <div class="truncate text-xs font-medium text-slate-700">{{ item.originalName }}</div>
                  <div class="mt-0.5 text-[11px] text-slate-400">
                    {{ item.cueCount }} 条<template v-if="item.targetLang"> · {{ item.targetLang }}</template> · {{ formatTime(item.updatedAt) }}
                  </div>
                </button>
              </li>
            </ul>
          </div>
        </div>

        <!-- 右栏：编辑工作区 -->
        <div class="rounded-card border border-slate-100 bg-white shadow-card">
          <div v-if="!current" class="flex h-full min-h-80 flex-col items-center justify-center p-10 text-center">
            <svg viewBox="0 0 24 24" class="h-12 w-12 text-slate-200" fill="none" stroke="currentColor" stroke-width="1.5">
              <rect x="3" y="5" width="18" height="14" rx="2" />
              <path d="M7 9h10M7 13h6M7 17h8" stroke-linecap="round" />
            </svg>
            <p class="mt-3 text-sm text-slate-400">上传字幕或点击左侧历史记录开始</p>
          </div>

          <template v-else>
            <!-- 工具栏 -->
            <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 p-5">
              <div class="min-w-0 flex-1">
                <div class="truncate text-sm font-semibold text-slate-800">{{ current.originalName }}</div>
                <div class="mt-0.5 text-xs text-slate-400">
                  {{ current.cues.length }} 条<template v-if="current.targetLang"> · 译文语言：{{ current.targetLang }}</template>
                </div>
              </div>
              <select
                v-model="targetLang"
                class="rounded-full border border-slate-200 bg-white px-4 py-2 text-xs text-slate-600 outline-none focus:border-primary"
              >
                <option v-for="lang in langs" :key="lang.id" :value="lang.name">{{ lang.name }}</option>
              </select>
              <button class="btn-primary px-5 py-2 text-xs" :disabled="translating" @click="doTranslate">
                <svg v-if="translating" class="h-3.5 w-3.5 animate-spin" viewBox="0 0 24 24" fill="none">
                  <circle cx="12" cy="12" r="10" stroke="rgba(255,255,255,0.3)" stroke-width="4" />
                  <path d="M22 12a10 10 0 00-10-10" stroke="currentColor" stroke-width="4" stroke-linecap="round" />
                </svg>
                {{ translating ? '翻译中，请稍候…' : '开始翻译' }}
              </button>
            </div>

            <!-- 字幕条表格 -->
            <div class="max-h-[55vh] overflow-auto">
              <table class="w-full text-xs">
                <thead class="sticky top-0 bg-slate-50 text-slate-400">
                  <tr>
                    <th class="w-12 px-3 py-2 text-left font-normal">#</th>
                    <th class="w-44 px-3 py-2 text-left font-normal">时间轴</th>
                    <th class="px-3 py-2 text-left font-normal">原文</th>
                    <th class="px-3 py-2 text-left font-normal">译文</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="cue in current.cues" :key="cue.index" class="border-t border-slate-50 align-top">
                    <td class="px-3 py-2 text-slate-400">{{ cue.index }}</td>
                    <td class="px-3 py-2 text-[11px] leading-5 text-slate-400">
                      {{ cue.start }}<br>→ {{ cue.end }}
                    </td>
                    <td class="px-2 py-1.5">
                      <textarea
                        v-model="cue.text"
                        rows="2"
                        class="w-full resize-y rounded-lg border border-transparent bg-slate-50 px-2 py-1.5 text-slate-700 outline-none transition focus:border-primary focus:bg-white"
                      ></textarea>
                    </td>
                    <td class="px-2 py-1.5">
                      <textarea
                        v-model="cue.translated"
                        rows="2"
                        placeholder="未翻译"
                        class="w-full resize-y rounded-lg border border-transparent bg-slate-50 px-2 py-1.5 text-slate-700 outline-none transition focus:border-primary focus:bg-white"
                      ></textarea>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 底部操作 -->
            <div class="flex flex-wrap items-center justify-end gap-3 border-t border-slate-100 p-4">
              <button class="text-xs text-slate-400 transition hover:text-red-500" @click="doDelete">删除</button>
              <button class="btn-ghost px-5 py-2 text-xs" :disabled="saving" @click="doSave">
                {{ saving ? '保存中…' : '保存修改' }}
              </button>
              <button class="btn-primary px-5 py-2 text-xs" @click="doDownload">下载 SRT</button>
            </div>
          </template>
        </div>
      </div>
    </template>
  </section>
</template>
