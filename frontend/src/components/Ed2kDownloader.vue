<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { isLoggedIn } from '../stores/auth'
import {
  fetchEd2kEngineHealth,
  accelerateEd2kEngine,
  createEd2kTask,
  fetchEd2kTasks,
  pauseEd2kTask,
  resumeEd2kTask,
  accelerateEd2kTask,
  deleteEd2kTask,
  downloadEd2kFileToBrowser
} from '../api/ed2k'

const health = ref({
  configured: false,
  connected: false,
  version: '',
  message: '正在检测电驴引擎状态...',
  ed2kConnected: null,
  highId: null,
  kadConnected: null,
  downloadSourceCount: null
})
const healthChecking = ref(false)
const engineAccelerating = ref(false)

const linkInput = ref('')
const submitting = ref(false)
const errorBanner = ref('')
const successBanner = ref('')

const tasks = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const listStale = ref(false)
const loadingList = ref(false)

const busyTaskIds = ref(new Set())
const confirmDeleteUuid = ref(null)
const downloadingTaskIds = ref(new Set())

let pollTimer = null

function isTaskBusy(taskUuid) {
  return busyTaskIds.value.has(taskUuid)
}

function setTaskBusy(taskUuid, busy) {
  const next = new Set(busyTaskIds.value)
  if (busy) next.add(taskUuid)
  else next.delete(taskUuid)
  busyTaskIds.value = next
}

function isFileDownloading(taskUuid) {
  return downloadingTaskIds.value.has(taskUuid)
}

function setFileDownloading(taskUuid, active) {
  const next = new Set(downloadingTaskIds.value)
  if (active) next.add(taskUuid)
  else next.delete(taskUuid)
  downloadingTaskIds.value = next
}

/** 前端即时解析预览 ed2k://|file|name|size|md4|/ */
const linkPreview = computed(() => {
  const raw = linkInput.value.trim()
  if (!raw) return null
  if (!raw.toLowerCase().startsWith('ed2k://|')) {
    return { valid: false, reason: '链接需以 ed2k://| 开头' }
  }
  if (!raw.endsWith('|/')) {
    return { valid: false, reason: '链接需以 |/ 结尾，且不支持尾部附加段' }
  }
  const body = raw.slice('ed2k://|'.length, -'|/'.length)
  const parts = body.split('|')
  if (parts[0]?.toLowerCase() !== 'file') {
    return { valid: false, reason: '仅支持 ed2k://|file| 文件下载链接' }
  }
  if (parts.length !== 4) {
    if (parts.length > 4 && parts[1] && /^[1-9][0-9]{0,18}$/.test(parts[2]) && /^[0-9a-fA-F]{32}$/.test(parts[3])) {
      return {
        valid: false,
        reason: '检测到尾部扩展段（如 h=/s=/p=），后端规范仅接收标准 4 段格式',
        cleanableLink: `ed2k://|file|${parts[1]}|${parts[2]}|${parts[3].toLowerCase()}|/`
      }
    }
    return { valid: false, reason: '格式应为 ed2k://|file|文件名|字节大小|32位MD4|/' }
  }
  const [, rawName, rawSize, rawMd4] = parts
  if (!rawName) {
    return { valid: false, reason: '文件名不能为空' }
  }
  if (!/^[1-9][0-9]{0,18}$/.test(rawSize)) {
    return { valid: false, reason: '文件大小必须为正整数' }
  }
  if (!/^[0-9a-fA-F]{32}$/.test(rawMd4)) {
    return { valid: false, reason: 'MD4 哈希必须为 32 位十六进制字符' }
  }
  let decodedName = rawName
  try {
    decodedName = decodeURIComponent(rawName)
  } catch {
    return { valid: false, reason: '文件名 URL 编码格式有误' }
  }
  return {
    valid: true,
    fileName: decodedName,
    fileSize: Number(rawSize),
    md4Hash: rawMd4.toLowerCase()
  }
})

const hasActiveTasks = computed(() =>
  tasks.value.some(t =>
    t.desiredState === 'ACTIVE' &&
    t.taskState !== 'COMPLETED' &&
    t.taskState !== 'ERROR'
  ) || tasks.value.some(t => t.pendingEngine)
)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function syncPollingState() {
  if (!isLoggedIn.value || !health.value.connected || !hasActiveTasks.value) {
    stopPolling()
    return
  }
  if (pollTimer === null) {
    pollTimer = setInterval(async () => {
      if (!isLoggedIn.value || !health.value.connected) {
        stopPolling()
        return
      }
      try {
        const res = await fetchEd2kTasks({ page: page.value, size: size.value })
        tasks.value = res.items || []
        total.value = res.total || 0
        listStale.value = !!res.stale
        if (!res.engineConnected) {
          health.value = {
            ...health.value,
            connected: false,
            message: 'aMule 连接已断开，轮询已暂停'
          }
          stopPolling()
        } else if (!hasActiveTasks.value) {
          stopPolling()
        }
      } catch (e) {
        // 轮询报错立即熔断，禁止控制台高频刷屏
        stopPolling()
        errorBanner.value = e.message || '刷新下载进度失败，已停止自动轮询'
      }
    }, 1000)
  }
}

async function checkEngineAndLoad() {
  if (!isLoggedIn.value) {
    stopPolling()
    return
  }
  healthChecking.value = true
  errorBanner.value = ''
  try {
    const h = await fetchEd2kEngineHealth()
    health.value = h
  } catch (e) {
    health.value = {
      configured: false,
      connected: false,
      version: '',
      message: e.message || '引擎状态检查失败'
    }
    stopPolling()
  } finally {
    healthChecking.value = false
  }

  await loadTasks()
}

async function loadTasks() {
  if (!isLoggedIn.value) return
  loadingList.value = true
  try {
    const res = await fetchEd2kTasks({ page: page.value, size: size.value })
    tasks.value = res.items || []
    total.value = res.total || 0
    listStale.value = !!res.stale
    syncPollingState()
  } catch (e) {
    stopPolling()
    errorBanner.value = e.message || '加载电驴下载列表失败'
  } finally {
    loadingList.value = false
  }
}

async function handleSubmit() {
  errorBanner.value = ''
  successBanner.value = ''
  const raw = linkInput.value.trim()
  if (!raw) {
    errorBanner.value = '请输入有效的 eD2k 下载链接'
    return
  }
  submitting.value = true
  try {
    await createEd2kTask(raw)
    linkInput.value = ''
    successBanner.value = '已提交电驴下载任务'
    page.value = 1
    await loadTasks()
  } catch (e) {
    errorBanner.value = e.message || '创建电驴下载任务失败'
    await loadTasks()
  } finally {
    submitting.value = false
  }
}

async function handlePause(task) {
  errorBanner.value = ''
  setTaskBusy(task.taskUuid, true)
  try {
    const updated = await pauseEd2kTask(task.taskUuid, task.revision)
    replaceTaskInList(updated)
    syncPollingState()
  } catch (e) {
    errorBanner.value = e.message || '暂停任务失败'
    await loadTasks()
  } finally {
    setTaskBusy(task.taskUuid, false)
  }
}

async function handleResume(task) {
  errorBanner.value = ''
  setTaskBusy(task.taskUuid, true)
  try {
    const updated = await resumeEd2kTask(task.taskUuid, task.revision)
    replaceTaskInList(updated)
    syncPollingState()
  } catch (e) {
    errorBanner.value = e.message || '恢复任务失败'
    await loadTasks()
  } finally {
    setTaskBusy(task.taskUuid, false)
  }
}

async function handleAccelerateEngine() {
  errorBanner.value = ''
  successBanner.value = ''
  engineAccelerating.value = true
  try {
    const res = await accelerateEd2kEngine()
    successBanner.value = res?.message || '已触发电驴双网重连与高速参数下发'
    await checkEngineAndLoad()
  } catch (e) {
    errorBanner.value = e.message || '引擎网络提速失败'
  } finally {
    engineAccelerating.value = false
  }
}

async function handleAccelerateTask(task) {
  errorBanner.value = ''
  successBanner.value = ''
  setTaskBusy(task.taskUuid, true)
  try {
    const updated = await accelerateEd2kTask(task.taskUuid)
    replaceTaskInList(updated)
    successBanner.value = `已对「${task.requestedName}」设置高优先级并执行 A4AF 寻源抢占`
    syncPollingState()
  } catch (e) {
    errorBanner.value = e.message || '加速寻源失败'
  } finally {
    setTaskBusy(task.taskUuid, false)
  }
}

function applyCleanableLink() {
  if (linkPreview.value?.cleanableLink) {
    linkInput.value = linkPreview.value.cleanableLink
  }
}

function askDelete(taskUuid) {
  confirmDeleteUuid.value = taskUuid
}

function cancelDelete() {
  confirmDeleteUuid.value = null
}

async function confirmDelete(task) {
  errorBanner.value = ''
  setTaskBusy(task.taskUuid, true)
  try {
    await deleteEd2kTask(task.taskUuid)
    confirmDeleteUuid.value = null
    await loadTasks()
  } catch (e) {
    errorBanner.value = e.message || '删除任务失败'
  } finally {
    setTaskBusy(task.taskUuid, false)
  }
}

async function handleDownloadFile(task) {
  errorBanner.value = ''
  setFileDownloading(task.taskUuid, true)
  try {
    await downloadEd2kFileToBrowser(task.taskUuid, 0, task.requestedName)
  } catch (e) {
    errorBanner.value = e.message || '取回已完成文件失败'
  } finally {
    setFileDownloading(task.taskUuid, false)
  }
}

function replaceTaskInList(updated) {
  if (!updated) return
  const idx = tasks.value.findIndex(item => item.taskUuid === updated.taskUuid)
  if (idx >= 0) {
    tasks.value.splice(idx, 1, updated)
  }
}

function changePage(nextPage) {
  if (nextPage < 1 || nextPage > totalPages.value) return
  page.value = nextPage
  loadTasks()
}

function formatBytes(bytes) {
  const n = Number(bytes || 0)
  if (n <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(n) / Math.log(1024)))
  const value = n / Math.pow(1024, i)
  return `${value.toFixed(i === 0 ? 0 : 2)} ${units[i]}`
}

function formatSpeed(bps) {
  const n = Number(bps || 0)
  if (n <= 0) return '0 B/s'
  return `${formatBytes(n)}/s`
}

function stateBadgeClass(taskState) {
  switch (taskState) {
    case 'COMPLETED':
      return 'bg-emerald-50 text-emerald-700 border-emerald-200'
    case 'DOWNLOADING':
      return 'bg-blue-50 text-blue-700 border-blue-200'
    case 'WAITING':
    case 'QUEUED':
    case 'VERIFYING':
      return 'bg-amber-50 text-amber-700 border-amber-200'
    case 'PAUSED':
      return 'bg-slate-100 text-slate-600 border-slate-200'
    case 'ERROR':
      return 'bg-red-50 text-red-700 border-red-200'
    default:
      return 'bg-slate-100 text-slate-600 border-slate-200'
  }
}

function stateLabel(taskState) {
  switch (taskState) {
    case 'QUEUED':
      return '排队中'
    case 'WAITING':
      return '等待源'
    case 'DOWNLOADING':
      return '下载中'
    case 'PAUSED':
      return '已暂停'
    case 'VERIFYING':
      return '校验中'
    case 'COMPLETED':
      return '已完成'
    case 'ERROR':
      return '异常'
    default:
      return taskState || '未知'
  }
}

watch(isLoggedIn, (logged) => {
  if (logged) {
    checkEngineAndLoad()
  } else {
    stopPolling()
    tasks.value = []
    total.value = 0
  }
})

onMounted(() => {
  if (isLoggedIn.value) {
    checkEngineAndLoad()
  }
})

onUnmounted(() => {
  stopPolling()
})
</script>

<template>
  <section class="mx-auto max-w-6xl px-4 py-8 sm:px-6 space-y-6">
    <!-- 引擎健康状态栏 -->
    <div class="bg-white rounded-2xl border border-slate-200/80 shadow-sm p-4 sm:p-5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <div class="flex items-start sm:items-center gap-3">
        <span
          class="mt-1 sm:mt-0 w-2.5 h-2.5 rounded-full shrink-0"
          :class="health.connected ? 'bg-emerald-500' : (health.configured ? 'bg-amber-500' : 'bg-slate-400')"
        />
        <div>
          <div class="flex flex-wrap items-center gap-2">
            <h2 class="text-base font-semibold text-slate-800">电驴下载 (eD2k / aMule)</h2>
            <span
              class="text-xs px-2 py-0.5 rounded-full border font-medium"
              :class="health.connected
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-amber-50 text-amber-700 border-amber-200'"
            >
              {{ health.connected ? `已连接${health.version ? ' v' + health.version : ''}` : (health.configured ? '未连接' : '未配置') }}
            </span>
            <span
              v-if="health.connected && health.ed2kConnected !== null && health.ed2kConnected !== undefined"
              class="text-xs px-2 py-0.5 rounded-full border font-medium"
              :class="health.ed2kConnected && health.highId
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-amber-50 text-amber-700 border-amber-200'"
            >
              {{ health.ed2kConnected ? (health.highId ? 'eD2k HighID' : 'eD2k LowID') : 'eD2k 未连接' }}
            </span>
            <span
              v-if="health.connected && health.kadConnected !== null && health.kadConnected !== undefined"
              class="text-xs px-2 py-0.5 rounded-full border font-medium"
              :class="health.kadConnected
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-amber-50 text-amber-700 border-amber-200'"
            >
              {{ health.kadConnected ? 'Kad 已连接' : 'Kad 未连接' }}
            </span>
            <span
              v-if="health.connected && health.downloadSourceCount != null"
              class="text-xs px-2 py-0.5 rounded-full bg-blue-50 text-blue-700 border border-blue-200"
            >
              全局源 {{ health.downloadSourceCount }}
            </span>
            <span
              v-if="listStale"
              class="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 border border-slate-200"
            >
              离线缓存快照
            </span>
          </div>
          <p class="text-xs text-slate-500 mt-0.5">{{ health.message }}</p>
        </div>
      </div>

      <div class="flex items-center gap-2 self-end sm:self-auto">
        <button
          v-if="health.configured"
          type="button"
          :disabled="engineAccelerating || healthChecking"
          class="px-3 py-1.5 text-xs font-medium rounded-lg border border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100 disabled:opacity-50 transition-colors"
          @click="handleAccelerateEngine"
        >
          {{ engineAccelerating ? '提速中...' : '网络提速 / 双网重连' }}
        </button>
        <button
          type="button"
          :disabled="healthChecking || loadingList"
          class="px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-200 text-slate-700 hover:bg-slate-50 disabled:opacity-50 transition-colors"
          @click="checkEngineAndLoad"
        >
          {{ healthChecking ? '检测中...' : '刷新状态' }}
        </button>
      </div>
    </div>

    <!-- 错误 / 成功提示 -->
    <div
      v-if="errorBanner"
      class="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-xl text-sm flex items-center justify-between gap-3"
    >
      <span>{{ errorBanner }}</span>
      <button type="button" class="text-xs text-red-500 hover:text-red-700 shrink-0" @click="errorBanner = ''">关闭</button>
    </div>

    <div
      v-if="successBanner"
      class="bg-emerald-50 border border-emerald-200 text-emerald-700 px-4 py-3 rounded-xl text-sm flex items-center justify-between gap-3"
    >
      <span>{{ successBanner }}</span>
      <button type="button" class="text-xs text-emerald-600 hover:text-emerald-800 shrink-0" @click="successBanner = ''">关闭</button>
    </div>

    <!-- 新建 eD2k 下载表单 -->
    <div class="bg-white rounded-2xl border border-slate-200/80 shadow-sm p-4 sm:p-6 space-y-4">
      <div>
        <label class="block text-sm font-medium text-slate-700 mb-1.5">提交单条 eD2k 文件链接</label>
        <div class="flex flex-col sm:flex-row gap-2.5">
          <input
            v-model="linkInput"
            type="text"
            placeholder="ed2k://|file|文件名|字节大小|32位MD4哈希|/"
            class="flex-1 px-3.5 py-2.5 text-sm rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/20 focus:border-blue-500"
            @keyup.enter="handleSubmit"
          />
          <button
            type="button"
            :disabled="submitting || !linkInput.trim()"
            class="px-5 py-2.5 text-sm font-medium rounded-xl bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors shrink-0"
            @click="handleSubmit"
          >
            {{ submitting ? '提交中...' : '创建下载' }}
          </button>
        </div>
      </div>

      <!-- 链接即时解析预览 -->
      <div v-if="linkPreview" class="rounded-xl p-3 text-xs border" :class="linkPreview.valid ? 'bg-slate-50 border-slate-200 text-slate-700' : 'bg-amber-50 border-amber-200 text-amber-800'">
        <template v-if="linkPreview.valid">
          <div class="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span><strong class="font-medium">文件名：</strong>{{ linkPreview.fileName }}</span>
            <span><strong class="font-medium">大小：</strong>{{ formatBytes(linkPreview.fileSize) }}</span>
            <span class="font-mono"><strong class="font-sans font-medium">MD4：</strong>{{ linkPreview.md4Hash }}</span>
          </div>
        </template>
        <template v-else>
          <div class="flex flex-wrap items-center justify-between gap-2">
            <span>格式提示：{{ linkPreview.reason }}</span>
            <button
              v-if="linkPreview.cleanableLink"
              type="button"
              class="px-2.5 py-1 text-xs font-medium rounded-lg bg-amber-600 text-white hover:bg-amber-700 transition-colors"
              @click="applyCleanableLink"
            >
              一键清理为标准链接
            </button>
          </div>
        </template>
      </div>
    </div>

    <!-- 任务列表 -->
    <div class="bg-white rounded-2xl border border-slate-200/80 shadow-sm overflow-hidden">
      <div class="px-4 sm:px-6 py-4 border-b border-slate-100 flex items-center justify-between">
        <h3 class="text-sm font-semibold text-slate-800">我的电驴任务 ({{ total }})</h3>
        <span v-if="loadingList" class="text-xs text-slate-400">加载中...</span>
      </div>

      <div v-if="tasks.length === 0 && !loadingList" class="p-10 text-center text-sm text-slate-400">
        暂无电驴下载任务，在上方粘贴 <code class="text-xs bg-slate-100 px-1.5 py-0.5 rounded">ed2k://|file|...|/</code> 链接即可开始下载
      </div>

      <div v-else class="divide-y divide-slate-100">
        <div
          v-for="task in tasks"
          :key="task.taskUuid"
          class="p-4 sm:p-5 space-y-3 hover:bg-slate-50/50 transition-colors"
        >
          <div class="flex flex-col sm:flex-row sm:items-start justify-between gap-2.5">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="text-sm font-medium text-slate-800 break-all">{{ task.requestedName }}</span>
                <span
                  class="text-xs px-2 py-0.5 rounded-full border font-medium"
                  :class="stateBadgeClass(task.taskState)"
                >
                  {{ stateLabel(task.taskState) }}
                </span>
                <span
                  v-if="task.pendingEngine"
                  class="text-xs px-2 py-0.5 rounded-full bg-amber-50 text-amber-700 border border-amber-200"
                >
                  引擎待同步
                </span>
                <span
                  v-if="task.stale"
                  class="text-xs px-2 py-0.5 rounded-full bg-slate-100 text-slate-600 border border-slate-200"
                >
                  快照待刷新
                </span>
              </div>

              <div class="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
                <span>{{ formatBytes(task.progressBytes) }} / {{ formatBytes(task.fileSize) }} ({{ task.progressPercent }}%)</span>
                <span v-if="task.taskState === 'DOWNLOADING'">速度：{{ formatSpeed(task.speedBps) }}</span>
                <span>源数：{{ task.sourcesTransferring }} / {{ task.sourcesTotal }}</span>
                <span class="font-mono text-slate-400">MD4: {{ task.md4Hash }}</span>
              </div>

              <p v-if="task.statusHint" class="mt-1 text-xs text-amber-600">
                {{ task.statusHint }}
              </p>
              <p v-if="task.errorMessage" class="mt-1 text-xs text-red-600">
                {{ task.errorMessage }}
              </p>
            </div>

            <!-- 操作按钮区 -->
            <div class="flex flex-wrap items-center gap-2 shrink-0">
              <button
                v-if="task.downloadable"
                type="button"
                :disabled="isFileDownloading(task.taskUuid)"
                class="px-3 py-1.5 text-xs font-medium rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50 transition-colors"
                @click="handleDownloadFile(task)"
              >
                {{ isFileDownloading(task.taskUuid) ? '取回中...' : '取回文件' }}
              </button>

              <button
                v-if="task.desiredState === 'ACTIVE' && task.taskState !== 'COMPLETED'"
                type="button"
                :disabled="isTaskBusy(task.taskUuid)"
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100 disabled:opacity-50 transition-colors"
                @click="handleAccelerateTask(task)"
              >
                加速寻源
              </button>

              <button
                v-if="task.desiredState === 'ACTIVE' && task.taskState !== 'COMPLETED'"
                type="button"
                :disabled="isTaskBusy(task.taskUuid)"
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-slate-200 text-slate-700 hover:bg-slate-100 disabled:opacity-50 transition-colors"
                @click="handlePause(task)"
              >
                暂停
              </button>

              <button
                v-if="task.desiredState === 'PAUSED' && task.taskState !== 'COMPLETED'"
                type="button"
                :disabled="isTaskBusy(task.taskUuid)"
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100 disabled:opacity-50 transition-colors"
                @click="handleResume(task)"
              >
                继续
              </button>

              <!-- 删除二次确认 -->
              <template v-if="confirmDeleteUuid === task.taskUuid">
                <button
                  type="button"
                  :disabled="isTaskBusy(task.taskUuid)"
                  class="px-2.5 py-1.5 text-xs font-medium rounded-lg bg-red-600 text-white hover:bg-red-700 disabled:opacity-50 transition-colors"
                  @click="confirmDelete(task)"
                >
                  确认删除
                </button>
                <button
                  type="button"
                  class="px-2.5 py-1.5 text-xs font-medium rounded-lg border border-slate-200 text-slate-600 hover:bg-slate-100 transition-colors"
                  @click="cancelDelete"
                >
                  取消
                </button>
              </template>
              <button
                v-else
                type="button"
                :disabled="isTaskBusy(task.taskUuid)"
                class="px-3 py-1.5 text-xs font-medium rounded-lg border border-red-200 text-red-600 hover:bg-red-50 disabled:opacity-50 transition-colors"
                @click="askDelete(task.taskUuid)"
              >
                删除
              </button>
            </div>
          </div>

          <!-- 进度条 -->
          <div class="w-full h-1.5 bg-slate-100 rounded-full overflow-hidden">
            <div
              class="h-full transition-all duration-300 rounded-full"
              :class="task.taskState === 'COMPLETED' ? 'bg-emerald-500' : (task.taskState === 'ERROR' ? 'bg-red-500' : 'bg-blue-500')"
              :style="{ width: `${Math.min(100, Math.max(0, task.progressPercent || 0))}%` }"
            />
          </div>
        </div>
      </div>

      <!-- 分页栏 -->
      <div
        v-if="totalPages > 1"
        class="px-4 sm:px-6 py-3 bg-slate-50/60 border-t border-slate-100 flex items-center justify-between text-xs text-slate-600"
      >
        <span>第 {{ page }} / {{ totalPages }} 页</span>
        <div class="flex items-center gap-2">
          <button
            type="button"
            :disabled="page <= 1"
            class="px-2.5 py-1 rounded border border-slate-200 bg-white hover:bg-slate-50 disabled:opacity-40"
            @click="changePage(page - 1)"
          >
            上一页
          </button>
          <button
            type="button"
            :disabled="page >= totalPages"
            class="px-2.5 py-1 rounded border border-slate-200 bg-white hover:bg-slate-50 disabled:opacity-40"
            @click="changePage(page + 1)"
          >
            下一页
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
