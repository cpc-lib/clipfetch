<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import {
  health as fetchHealth,
  submitMagnet,
  submitTorrent,
  parseDetail,
  createTask as createTaskApi,
  listTasks,
  taskDetail,
  pauseTask,
  resumeTask,
  deleteTask as deleteTaskApi,
  fileTicket,
  downloadTicketBlob
} from '../api/magnetV2'

const engineHealth = ref({ configured: false, connected: false })
const error = ref('')
const formError = ref('')
const busy = ref(false)
const magnetUri = ref('')
const parse = ref(null)
const parseUuid = ref('')
const selectedFileIndexes = ref([])
const tasks = ref([])

let healthTimer = null
let parseTimer = null
let taskTimer = null

function stopParseTimer() {
  if (parseTimer !== null) {
    clearInterval(parseTimer)
    parseTimer = null
  }
}

function stopTaskTimer() {
  if (taskTimer !== null) {
    clearInterval(taskTimer)
    taskTimer = null
  }
}

function stopAllTimers() {
  stopParseTimer()
  stopTaskTimer()
  if (healthTimer !== null) {
    clearInterval(healthTimer)
    healthTimer = null
  }
}

function connected() {
  return Boolean(engineHealth.value?.configured && engineHealth.value?.connected)
}

function syncTimers() {
  if (!connected()) {
    stopParseTimer()
    stopTaskTimer()
    return
  }
  if (taskTimer === null) {
    taskTimer = setInterval(loadTasks, 1000)
  }
  if (parseUuid.value && parseTimer === null) {
    parseTimer = setInterval(() => pollParse(parseUuid.value), 2000)
  }
}

async function refreshHealth() {
  try {
    engineHealth.value = await fetchHealth()
    if (!connected()) {
      error.value = engineHealth.value?.message || ''
    }
    syncTimers()
  } catch (err) {
    stopParseTimer()
    stopTaskTimer()
    error.value = err.message || '引擎状态检查失败'
  }
}

async function loadTasks() {
  if (!connected()) return
  try {
    const list = await listTasks()
    tasks.value = list.map(item => {
      const existing = tasks.value.find(t => t.taskUuid === item.taskUuid)
      return existing?.files?.length ? { ...item, files: existing.files } : item
    })
  } catch (err) {
    stopTaskTimer()
    error.value = err.message || '任务列表获取失败'
  }
}

async function pollParse(uuid) {
  try {
    const detail = await parseDetail(uuid)
    parse.value = detail
    if (detail.status === 'READY') {
      selectedFileIndexes.value = detail.files.map(file => file.fileIndex)
      stopParseTimer()
    } else if (detail.status === 'FAILED' || detail.status === 'TIMEOUT') {
      error.value = detail.errorMessage || '解析失败'
      stopParseTimer()
    }
  } catch (err) {
    stopParseTimer()
    error.value = err.message || '解析状态获取失败'
  }
}

async function parseMagnet() {
  if (!magnetUri.value.trim()) {
    formError.value = '请输入磁力链接'
    return
  }
  busy.value = true
  try {
    const job = await submitMagnet(magnetUri.value.trim())
    parseUuid.value = job.parseUuid
    parse.value = job
    formError.value = ''
    syncTimers()
  } catch (err) {
    formError.value = err.message || '提交解析失败'
  } finally {
    busy.value = false
  }
}

async function uploadTorrent(event) {
  const file = event.target.files?.[0]
  if (!file) return
  busy.value = true
  try {
    const job = await submitTorrent(file)
    parseUuid.value = job.parseUuid
    parse.value = job
    formError.value = ''
    syncTimers()
  } catch (err) {
    formError.value = err.message || '上传种子失败'
  } finally {
    busy.value = false
    event.target.value = ''
  }
}

function toggleFile(fileIndex) {
  if (selectedFileIndexes.value.includes(fileIndex)) {
    selectedFileIndexes.value = selectedFileIndexes.value.filter(index => index !== fileIndex)
  } else {
    selectedFileIndexes.value = [...selectedFileIndexes.value, fileIndex]
  }
}

async function createTask() {
  if (!selectedFileIndexes.value.length) {
    formError.value = '请至少选择一个文件'
    return
  }
  if (!parseUuid.value) {
    formError.value = '请先解析资源'
    return
  }
  busy.value = true
  try {
    const task = await createTaskApi(parseUuid.value, selectedFileIndexes.value)
    tasks.value = [task, ...tasks.value]
    formError.value = ''
  } catch (err) {
    formError.value = err.message || '创建任务失败'
  } finally {
    busy.value = false
  }
}

async function togglePause(task) {
  try {
    const updated = task.status === 'PAUSED' || task.status === 'PAUSING'
      ? await resumeTask(task.taskUuid)
      : await pauseTask(task.taskUuid)
    tasks.value = tasks.value.map(item => item.taskUuid === updated.taskUuid ? updated : item)
  } catch (err) {
    formError.value = err.message || '任务操作失败'
  }
}

async function deleteTask(task) {
  if (!window.confirm('确定要删除该下载任务吗？此操作不可撤销。')) return
  try {
    await deleteTaskApi(task.taskUuid)
    tasks.value = tasks.value.filter(item => item.taskUuid !== task.taskUuid)
  } catch (err) {
    formError.value = err.message || '任务删除失败'
  }
}

async function showFiles(task) {
  try {
    const detail = await taskDetail(task.taskUuid)
    tasks.value = tasks.value.map(item => item.taskUuid === detail.taskUuid ? detail : item)
  } catch (err) {
    formError.value = err.message || '任务详情获取失败'
  }
}

function fileNameOf(file) {
  const segments = String(file.relativePath || '').replace(/\\/g, '/').split('/')
    .filter(segment => segment && segment !== '.' && segment !== '..')
  return segments.pop() || `file-${file.fileIndex}`
}

async function download(task, file) {
  try {
    // The stream endpoint is login-protected, so a bare <a href> would 401.
    // Fetch it through axios with the JWT, then hand the blob to the browser.
    const url = await fileTicket(task.taskUuid, file.fileIndex)
    const blob = await downloadTicketBlob(url)
    const objectUrl = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = objectUrl
    a.download = fileNameOf(file)
    a.rel = 'noopener'
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(objectUrl), 3000)
  } catch (err) {
    formError.value = err.message || '下载文件失败'
  }
}

async function downloadTask(task) {
  if (!task.files?.length) {
    try {
      const detail = await taskDetail(task.taskUuid)
      task.files = detail.files
    } catch (err) {
      formError.value = err.message || '任务详情获取失败'
      return
    }
  }
  const done = task.files.filter(f => f.state === 'COMPLETE')
  if (!done.length) {
    formError.value = '暂无可下载的文件'
    return
  }
  for (const file of done) {
    await download(task, file)
    await new Promise(r => setTimeout(r, 300))
  }
}

function fileProgress(file) {
  if (!file.declaredBytes || file.declaredBytes <= 0) return 0
  return Math.min(100, Math.round(file.verifiedBytes * 100 / file.declaredBytes))
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = bytes
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${units[i]}`
}

const STATUS_LABELS = {
  QUEUED: '排队中',
  RUNNING: '下载中',
  PAUSING: '暂停中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  FAILED: '失败',
  TIMEOUT: '超时'
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

onMounted(() => {
  healthTimer = setInterval(refreshHealth, 5000)
  refreshHealth()
})

onUnmounted(() => {
  stopAllTimers()
})

defineExpose({ pollParse, createTask, loadTasks, refreshHealth, selectedFileIndexes })
</script>

<template>
  <section class="mx-auto max-w-7xl px-4 pb-8 sm:px-6">
    <div class="rounded-card border border-slate-100 bg-white p-4 shadow-card">
      <div class="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 class="text-lg font-semibold text-slate-800">Native v2 下载</h2>
          <p class="mt-1 text-xs text-slate-500">Magnet 元数据解析、文件选择与断点续传由 libtorrent Worker 完成。</p>
        </div>
        <span class="text-xs" :class="connected() ? 'text-emerald-600' : 'text-amber-600'">
          {{ connected() ? 'Worker 已连接' : 'Worker 未连接' }}
        </span>
      </div>

      <p v-if="error" role="alert" class="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700">
        {{ error }}
      </p>

      <div class="mt-4 grid gap-3 lg:grid-cols-2">
        <div class="rounded-xl border border-slate-200 p-3">
          <label class="text-sm font-medium text-slate-700" for="v2-magnet">磁力链接</label>
          <textarea id="v2-magnet" v-model="magnetUri" rows="3"
                    class="mt-2 w-full rounded-lg border border-slate-200 p-2 text-sm"
                    placeholder="magnet:?xt=urn:btih:..." />
          <button type="button" :disabled="busy"
                  class="mt-2 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
                  @click="parseMagnet">解析磁力</button>
        </div>
        <div class="rounded-xl border border-slate-200 p-3">
          <label class="text-sm font-medium text-slate-700" for="v2-torrent">上传 .torrent</label>
          <input id="v2-torrent" type="file" accept=".torrent" class="mt-2 block w-full text-sm"
                 :disabled="busy" @change="uploadTorrent" />
          <p class="mt-2 text-xs text-slate-500">只接受公开种子，private torrent 会被拒绝。</p>
        </div>
      </div>

      <div v-if="parse" class="mt-4 rounded-xl border border-slate-200 p-3">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="text-sm font-medium text-slate-800">{{ parse.name || '解析中…' }}</span>
          <span class="text-xs text-slate-500">{{ parse.status }}</span>
        </div>
        <p v-if="parse.totalBytes" class="mt-1 text-xs text-slate-500">
          共 {{ (parse.files || []).length }} 个文件 · {{ parse.totalBytes }} 字节
        </p>
        <ul v-if="(parse.files || []).length" class="mt-3 space-y-1">
          <li v-for="file in parse.files" :key="file.fileIndex" class="flex items-center gap-2 text-sm">
            <input :id="`v2-file-${file.fileIndex}`" type="checkbox"
                   :checked="selectedFileIndexes.includes(file.fileIndex)"
                   @change="toggleFile(file.fileIndex)" />
            <label :for="`v2-file-${file.fileIndex}`" class="flex-1 truncate text-slate-700">
              {{ file.relativePath }}
            </label>
            <span class="text-xs text-slate-500">{{ file.bytes }} B</span>
          </li>
        </ul>
        <button v-if="parse.status === 'READY'" type="button" :disabled="busy"
                class="mt-3 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-white disabled:opacity-50"
                @click="createTask">创建下载任务</button>
      </div>

      <p v-if="formError" role="alert" class="mt-3 text-sm text-amber-700">{{ formError }}</p>
    </div>

    <div class="mt-4 rounded-card border border-slate-100 bg-white p-4 shadow-card">
      <div class="flex items-center justify-between">
        <h3 class="text-sm font-semibold text-slate-800">我的任务</h3>
        <button type="button" class="text-xs text-primary" @click="loadTasks">刷新</button>
      </div>
      <ul class="mt-3 space-y-3">
        <li v-for="task in tasks" :key="task.taskUuid" class="rounded-xl border border-slate-200 p-3">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <span class="text-sm font-medium text-slate-800">{{ task.taskUuid }}</span>
            <span class="text-xs text-slate-500">{{ statusLabel(task.status) }} · {{ task.progressPercent ?? 0 }}%</span>
          </div>
          <div class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
            <div class="h-full bg-primary" :style="{ width: `${task.progressPercent ?? 0}%` }" />
          </div>
          <p v-if="task.errorMessage" class="mt-2 text-xs text-amber-700">{{ task.errorMessage }}</p>
          <div class="mt-2 flex flex-wrap gap-2 text-xs">
            <button type="button" class="rounded-lg border border-slate-200 px-2 py-1 text-slate-600"
                    @click="togglePause(task)">
              {{ task.status === 'PAUSED' || task.status === 'PAUSING' ? '继续' : '暂停' }}
            </button>
            <button v-if="task.status === 'COMPLETED'" type="button"
                    class="rounded-lg bg-primary px-2.5 py-1 text-white transition hover:bg-primary/90"
                    @click="downloadTask(task)">下载</button>
            <button v-if="task.status === 'COMPLETED'" type="button"
                    class="rounded-lg border border-slate-200 px-2 py-1 text-slate-600"
                    @click="showFiles(task)">查看文件</button>
            <button type="button" class="rounded-lg border border-rose-200 px-2 py-1 text-rose-600"
                    @click="deleteTask(task)">删除</button>
          </div>
          <ul v-if="task.files?.length" class="mt-2 space-y-1">
            <li v-for="file in task.files" :key="file.fileIndex"
                class="flex items-center justify-between gap-2 rounded-lg bg-slate-50 px-2 py-1.5 text-xs">
              <div class="flex min-w-0 flex-1 items-center gap-2">
                <span class="shrink-0 rounded bg-slate-200 px-1.5 py-0.5 text-[10px] text-slate-600">{{ file.fileIndex }}</span>
                <span class="truncate text-slate-700">{{ file.relativePath || `文件 ${file.fileIndex}` }}</span>
              </div>
              <span class="shrink-0 text-[11px] text-slate-400">{{ formatBytes(file.declaredBytes) }}</span>
              <button v-if="file.state === 'COMPLETE'" type="button"
                      class="shrink-0 rounded-full bg-emerald-500 px-2.5 py-0.5 text-[11px] font-medium text-white transition hover:bg-emerald-600"
                      @click="download(task, file)">
                {{ fileProgress(file) }}%
              </button>
              <span v-else class="shrink-0 rounded-full bg-slate-200 px-2.5 py-0.5 text-[11px] font-medium text-slate-500">
                {{ fileProgress(file) }}%
              </span>
            </li>
          </ul>
        </li>
        <li v-if="!tasks.length" class="text-sm text-slate-500">暂无任务</li>
      </ul>
    </div>
  </section>
</template>
