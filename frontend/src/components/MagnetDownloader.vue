<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import {
  parseMagnet,
  parseMagnetTorrent,
  parseMagnetDetail,
  fetchMagnetHistory,
  deleteMagnetHistory,
  createMagnetDownload,
  fetchMagnetDownloads,
  startMagnetDownload,
  pauseMagnetDownload,
  removeMagnetDownload,
  magnetEngineHealth,
  fetchCompletedFiles,
  downloadFileToBrowser
} from '../api/magnet'

// ==================== 引擎状态 ====================
const engine = ref(null) // {version, savePath}
const engineError = ref('')

async function checkEngine() {
  engineError.value = ''
  try {
    const res = await magnetEngineHealth()
    if (!res || !res.version || res.connected === false) {
      engine.value = null
      engineError.value = res?.message || 'qBittorrent 未就绪或未配置'
    } else {
      engine.value = res
    }
  } catch (e) {
    engine.value = null
    engineError.value = e.message || '连接失败'
  }
}

// ==================== 磁力解析 ====================
const magnetUrl = ref('')
const submitting = ref(false)
const torrentUploading = ref(false)
const current = ref(null) // 当前解析记录
const parseError = ref('')
let pollToken = 0

/** 顶栏「新建」下拉菜单 */
const newMenuOpen = ref(false)
const menuWrap = ref(null)
/** 「新建链接任务」对话框 */
const linkDialogOpen = ref(false)

function openLinkDialog() {
  newMenuOpen.value = false
  parseError.value = ''
  linkDialogOpen.value = true
}

function closeLinkDialog() {
  linkDialogOpen.value = false
  parseError.value = ''
}

/** 点击菜单外部时收起下拉 */
function onDocClick(e) {
  if (newMenuOpen.value && menuWrap.value && !menuWrap.value.contains(e.target)) {
    newMenuOpen.value = false
  }
}

/** Ctrl+N 快捷键：新建链接任务 */
function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 'n' || e.key === 'N')) {
    e.preventDefault()
    openLinkDialog()
  }
}

async function submitParse() {
  const url = magnetUrl.value.trim()
  if (!url) {
    parseError.value = '请输入磁力链接'
    return
  }
  if (!url.startsWith('magnet:?')) {
    parseError.value = '请输入合法的磁力链接（magnet:?xt=...）'
    return
  }
  submitting.value = true
  parseError.value = ''
  try {
    const row = await parseMagnet(url)
    linkDialogOpen.value = false
    applyParseRow(row)
    if (row.status === 'PARSING') pollParse(row.id)
  } catch (e) {
    parseError.value = e.message || '解析提交失败'
  } finally {
    submitting.value = false
  }
}

/** 选择本地 .torrent 文件并上传解析（与磁力链接等价的提交方式） */
function pickTorrentFile() {
  newMenuOpen.value = false
  if (torrentUploading.value) return
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.torrent,application/x-bittorrent'
  input.onchange = () => {
    if (input.files && input.files[0]) submitTorrent(input.files[0])
  }
  input.click()
}

async function submitTorrent(file) {
  if (!/\.torrent$/i.test(file.name)) {
    parseError.value = '请选择 .torrent 种子文件'
    return
  }
  torrentUploading.value = true
  parseError.value = ''
  try {
    const row = await parseMagnetTorrent(file)
    magnetUrl.value = ''
    applyParseRow(row)
    if (row.status === 'PARSING') pollParse(row.id)
  } catch (e) {
    parseError.value = e.message || '上传种子解析失败'
  } finally {
    torrentUploading.value = false
  }
}

function applyParseRow(row) {
  current.value = row
  files.value = []
  selected.value = new Set()
  expandedDirs.value = new Set()
  if (row.status === 'SUCCEEDED' && row.filesJson) {
    try {
      files.value = JSON.parse(row.filesJson)
      // 默认全选，用户可取消不需要的文件
      selected.value = new Set(files.value.map(f => f.index))
      // 默认展开全部目录，避免文件被折叠隐藏
      const dirs = new Set()
      collectDirPaths(tree.value, dirs)
      expandedDirs.value = dirs
    } catch {
      files.value = []
    }
  }
  if (row.status === 'FAILED') {
    parseError.value = row.error || '解析失败'
  }
}

async function pollParse(id) {
  const token = ++pollToken
  while (token === pollToken) {
    await new Promise(r => setTimeout(r, 2000))
    if (token !== pollToken) return
    try {
      const row = await parseMagnetDetail(id)
      if (token !== pollToken) return
      current.value = row
      if (row.status === 'SUCCEEDED') {
        applyParseRow(row)
        return
      }
      if (row.status === 'FAILED') {
        parseError.value = row.error || '解析失败'
        return
      }
    } catch {
      // 轮询失败不中断，下一轮重试
    }
  }
}

// ==================== 文件选择与下载 ====================
const files = ref([])
const selected = ref(new Set())
/** 已展开的目录 path 集合（解析结果以目录树展示） */
const expandedDirs = ref(new Set())
const downloadError = ref('')
const starting = ref(false)

function toggleFile(index) {
  const s = new Set(selected.value)
  if (s.has(index)) s.delete(index)
  else s.add(index)
  selected.value = s
}

function toggleAllFiles() {
  selected.value = selected.value.size === files.value.length
    ? new Set()
    : new Set(files.value.map(f => f.index))
}

const allSelected = computed(() => files.value.length > 0 && selected.value.size === files.value.length)

// ---------- 文件清单 → 目录树（与迅雷解析结果一致，展示目录层级） ----------
function isSelected(index) {
  return selected.value.has(index)
}

/** 由文件的完整相对路径构建目录树；文件节点带 index，目录节点聚合大小与子文件索引 */
function buildTree(list) {
  const root = { type: 'dir', name: '', path: '', children: [], size: 0, fileCount: 0, fileIndexes: [] }
  for (const f of list) {
    const parts = String(f.path || '').split('/').filter(Boolean)
    let node = root
    let acc = ''
    parts.forEach((part, i) => {
      acc = acc ? `${acc}/${part}` : part
      if (i === parts.length - 1) {
        node.children.push({ type: 'file', name: part, path: acc, size: f.size || 0, index: f.index, fileIndexes: [f.index] })
      } else {
        let dir = node.children.find(c => c.type === 'dir' && c.name === part)
        if (!dir) {
          dir = { type: 'dir', name: part, path: acc, children: [], size: 0, fileCount: 0, fileIndexes: [] }
          node.children.push(dir)
        }
        node = dir
      }
    })
  }
  aggregate(root)
  sortTree(root)
  return root
}

function aggregate(node) {
  if (node.type !== 'dir') return
  let size = 0
  const idxs = []
  for (const c of node.children) {
    aggregate(c)
    size += c.size
    idxs.push(...c.fileIndexes)
  }
  node.size = size
  node.fileCount = idxs.length
  node.fileIndexes = idxs
}

function sortTree(node) {
  if (node.type !== 'dir') return
  node.children.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1))
  node.children.forEach(sortTree)
}

function collectDirPaths(node, out) {
  if (node.type !== 'dir') return
  if (node.path) out.add(node.path)
  node.children.forEach(c => collectDirPaths(c, out))
}

const tree = computed(() => buildTree(files.value))

/** 依据展开状态把树拍平成可渲染的行（depth 控制缩进） */
const visibleNodes = computed(() => {
  const out = []
  const walk = (node, depth) => {
    for (const c of node.children) {
      out.push({ ...c, depth })
      if (c.type === 'dir' && expandedDirs.value.has(c.path)) walk(c, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

/** 目录勾选态：全选/半选（由已选文件索引推导） */
const dirStates = computed(() => {
  const m = new Map()
  for (const n of visibleNodes.value) {
    if (n.type !== 'dir') continue
    let sel = 0
    for (const i of n.fileIndexes) if (selected.value.has(i)) sel++
    m.set(n.path, {
      checked: n.fileIndexes.length > 0 && sel === n.fileIndexes.length,
      indeterminate: sel > 0 && sel < n.fileIndexes.length
    })
  }
  return m
})

function dirState(node) {
  return dirStates.value.get(node.path) || { checked: false, indeterminate: false }
}

function isDirExpanded(path) {
  return expandedDirs.value.has(path)
}

function toggleDirExpand(node) {
  const s = new Set(expandedDirs.value)
  if (s.has(node.path)) s.delete(node.path)
  else s.add(node.path)
  expandedDirs.value = s
}

function toggleDir(node) {
  const all = dirState(node).checked
  const s = new Set(selected.value)
  for (const i of node.fileIndexes) {
    if (all) s.delete(i)
    else s.add(i)
  }
  selected.value = s
}

async function startDownload() {
  if (selected.value.size === 0) {
    downloadError.value = '请至少勾选一个文件'
    return
  }
  starting.value = true
  downloadError.value = ''
  try {
    await createMagnetDownload({ parseId: current.value.id, selectedFileIndexes: [...selected.value] })
    magnetUrl.value = ''
    await loadDownloads()
  } catch (e) {
    downloadError.value = e.message || '创建下载失败'
  } finally {
    starting.value = false
  }
}

/** 对没有 .torrent 缓存的旧记录提供明确的重新解析入口。 */
async function retryParseMetadata() {
  if (!current.value?.magnetUri || submitting.value) return
  submitting.value = true
  downloadError.value = ''
  parseError.value = ''
  try {
    const row = await parseMagnet(current.value.magnetUri, true)
    applyParseRow(row)
    if (row.status === 'PARSING') pollParse(row.id)
    await loadHistory()
  } catch (e) {
    parseError.value = e.message || '重新解析失败'
  } finally {
    submitting.value = false
  }
}

// ==================== 下载管理（每秒轮询） ====================
const downloads = ref([])
const downloadsError = ref('')
let pollTimer = null

/** 下载任务检索与分页：任务本身为每秒全量轮询，过滤/分页在前端本地完成 */
const downloadsKeyword = ref('')
const downloadsQuery = ref('')
const downloadsPage = ref(1)
const downloadsSize = ref(Number(localStorage.getItem('magnet.downloads.size')) || 10)
const downloadsStatus = ref(localStorage.getItem('magnet.downloads.status') || '')

// 已完成文件列表缓存：{ hash: [{index, path, size}] }
const completedFilesMap = ref({})

async function loadDownloads() {
  if (document.hidden) return
  if (!engine.value || !engine.value.version || engine.value.connected === false) return
  try {
    downloads.value = await fetchMagnetDownloads()
    downloadsError.value = ''
    // 任务被删除后当前页可能越界，回退到最后一页
    if (downloadsPage.value > downloadsTotalPages.value) downloadsPage.value = downloadsTotalPages.value
  } catch (e) {
    downloadsError.value = e.message || '查询下载任务失败'
    if (e.response?.status === 400 || e.response?.status === 500) {
      engine.value = null
      engineError.value = e.message || '引擎连接异常'
    }
  }
}

/** 按状态筛选 + 按名称模糊过滤（关键词在点击「查询」或回车后生效） */
const filteredDownloads = computed(() => {
  let list = downloads.value
  if (downloadsStatus.value === 'completed') list = list.filter(d => d.progressPercent >= 100)
  else if (downloadsStatus.value === 'downloading') list = list.filter(d => d.progressPercent < 100)
  const kw = downloadsQuery.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(d => String(d.name || '').toLowerCase().includes(kw))
})

const downloadsTotalPages = computed(() => Math.max(1, Math.ceil(filteredDownloads.value.length / downloadsSize.value)))

const pagedDownloads = computed(() => {
  const start = (downloadsPage.value - 1) * downloadsSize.value
  return filteredDownloads.value.slice(start, start + downloadsSize.value)
})

function searchDownloads() {
  downloadsQuery.value = downloadsKeyword.value
  downloadsPage.value = 1
}

watch(downloadsSize, v => {
  localStorage.setItem('magnet.downloads.size', String(v))
  downloadsPage.value = 1
})

watch(downloadsStatus, v => {
  localStorage.setItem('magnet.downloads.status', v)
  downloadsPage.value = 1
})

/** 加载指定任务的已完成文件列表 */
async function loadCompletedFiles(hash) {
  try {
    const files = await fetchCompletedFiles(hash)
    completedFilesMap.value = { ...completedFilesMap.value, [hash]: files }
  } catch (e) {
    console.warn('加载已完成文件失败:', e)
  }
}

/** 浏览器原生下载：网络断开后可以通过 HTTP Range 续传，不缓存全文件 Blob。 */
async function browserDownload(item, file) {
  try {
    await downloadFileToBrowser(item.infoHash, file.index)
  } catch (e) {
    downloadsError.value = e.message || '创建浏览器下载失败'
  }
}

/** 展开/收起已完成文件列表 */
const expandedFiles = ref(new Set())
function isExpanded(hash) {
  return expandedFiles.value.has(hash)
}
function toggleExpand(hash) {
  const s = new Set(expandedFiles.value)
  if (s.has(hash)) {
    s.delete(hash)
  } else {
    s.add(hash)
    // 展开时加载文件列表
    if (!completedFilesMap.value[hash]) {
      loadCompletedFiles(hash)
    }
  }
  expandedFiles.value = s
}

const PAUSED_STATES = ['pausedDL', 'stoppedDL', 'pausedUP', 'stoppedUP']
const DONE_STATES = ['uploading', 'stalledUP', 'forcedUP', 'pausedUP', 'stoppedUP']

function stateLabel(state) {
  const map = {
    downloading: '下载中', downloadingMetadata: '获取元数据', metaDL: '获取元数据', forcedMetaDL: '获取元数据',
    stalledDL: '连接中', forcedDL: '下载中', pausedDL: '已暂停', stoppedDL: '已暂停',
    uploading: '做种中', stalledUP: '已完成', forcedUP: '做种中', pausedUP: '已完成', stoppedUP: '已完成',
    queuedDL: '排队中', queuedUP: '排队中', checkingDL: '校验中', checkingUP: '校验中',
    checkingResumeData: '校验中', moving: '移动中', error: '错误', missingFiles: '文件缺失', unknown: '未知'
  }
  return map[state] || state
}

function stateClass(state) {
  if (DONE_STATES.includes(state)) return 'bg-green-50 text-green-600'
  if (PAUSED_STATES.includes(state)) return 'bg-slate-100 text-slate-500'
  if (state === 'error' || state === 'missingFiles') return 'bg-red-50 text-red-500'
  return 'bg-primary-light text-primary'
}

async function resumeOne(item) {
  try {
    await startMagnetDownload(item.infoHash)
    loadDownloads()
  } catch (e) {
    downloadsError.value = e.message || '操作失败'
  }
}

async function pauseOne(item) {
  try {
    await pauseMagnetDownload(item.infoHash)
    loadDownloads()
  } catch (e) {
    downloadsError.value = e.message || '操作失败'
  }
}

// 删除弹窗：默认仅删除任务保留文件，勾选后连同文件一起删除
const deleting = ref(null)
const deleteFiles = ref(false)

function openDelete(item) {
  deleting.value = item
  deleteFiles.value = false
}

async function confirmDelete() {
  try {
    await removeMagnetDownload(deleting.value.infoHash, deleteFiles.value)
    deleting.value = null
    loadDownloads()
  } catch (e) {
    downloadsError.value = e.message || '删除失败'
    deleting.value = null
  }
}

// ==================== 解析历史 ====================
const historyList = ref([])
const historyTotal = ref(0)
const historyPage = ref(1)
const historySize = ref(Number(localStorage.getItem('magnet.size')) || 10)
const historyKeyword = ref('')
const historyLoading = ref(false)

async function loadHistory() {
  historyLoading.value = true
  try {
    const data = await fetchMagnetHistory({
      page: historyPage.value,
      size: historySize.value,
      keyword: historyKeyword.value.trim() || undefined
    })
    historyList.value = data.records || []
    historyTotal.value = data.total || 0
  } catch {
    // 历史加载失败不打断主流程
  } finally {
    historyLoading.value = false
  }
}

function searchHistory() {
  historyPage.value = 1
  loadHistory()
}

function openHistory(item) {
  if (item.status !== 'SUCCEEDED' || !item.filesJson) {
    parseError.value = '该记录未解析成功，请重新解析磁力链接'
    return
  }
  magnetUrl.value = item.magnetUri
  applyParseRow(item)
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function removeHistory(item) {
  if (!confirm(`确认删除解析历史「${item.name || item.infoHash}」？（不影响已下载的文件）`)) return
  try {
    await deleteMagnetHistory(item.id)
    if (historyList.value.length === 1 && historyPage.value > 1) historyPage.value--
    else loadHistory()
  } catch (e) {
    parseError.value = e.message || '删除失败'
  }
}

const historyTotalPages = computed(() => Math.max(1, Math.ceil(historyTotal.value / historySize.value)))

watch([historyPage, historySize], loadHistory)
watch(historySize, v => localStorage.setItem('magnet.size', String(v)))

// ==================== 格式化 ====================
function formatSize(bytes) {
  if (bytes == null || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  if (bytes < 1024 ** 4) return `${(bytes / 1024 ** 3).toFixed(2)} GB`
  return `${(bytes / 1024 ** 4).toFixed(2)} TB`
}

function formatSpeed(bytesPerSec) {
  if (!bytesPerSec) return '-'
  if (bytesPerSec < 1024) return `${bytesPerSec} B/s`
  if (bytesPerSec < 1024 ** 2) return `${(bytesPerSec / 1024).toFixed(1)} KB/s`
  return `${(bytesPerSec / 1024 ** 2).toFixed(1)} MB/s`
}

function formatEta(seconds) {
  if (seconds == null || seconds < 0 || seconds >= 8640000) return '-'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}时${m}分`
  if (m > 0) return `${m}分${s}秒`
  return `${s}秒`
}

function formatDate(dt) {
  return dt ? String(dt).replace('T', ' ').slice(0, 19) : '-'
}

onMounted(async () => {
  await checkEngine()
  await loadHistory()
  if (engine.value && engine.value.version && engine.value.connected !== false) {
    await loadDownloads()
  }
  pollTimer = setInterval(loadDownloads, 1000)
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  pollToken++
  if (pollTimer) clearInterval(pollTimer)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <section class="mx-auto max-w-7xl px-4 py-8 sm:px-6">
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <h1 class="text-xl font-bold text-slate-800">磁力下载</h1>
      <div class="flex flex-wrap items-center gap-3 text-sm">
        <!-- qBittorrent 引擎状态 -->
        <div class="flex items-center gap-2">
          <template v-if="engine">
            <span class="h-2 w-2 rounded-full bg-green-500"></span>
            <span class="text-slate-500">qBittorrent 已连接 · v{{ engine.version }}</span>
          </template>
          <template v-else>
            <span class="h-2 w-2 rounded-full bg-red-500"></span>
            <span class="text-red-500">qBittorrent 未连接{{ engineError ? '：' + engineError : '' }}</span>
            <button class="text-primary hover:underline" @click="checkEngine">重试</button>
          </template>
        </div>
        <!-- 新建任务下拉菜单 -->
        <div ref="menuWrap" class="relative">
          <button
            class="inline-flex items-center gap-1.5 rounded-lg bg-primary-light px-4 py-2 font-medium text-primary transition hover:opacity-90"
            @click="newMenuOpen = !newMenuOpen"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 5v14M5 12h14" stroke-linecap="round" />
            </svg>
            新建
          </button>
          <div
            v-if="newMenuOpen"
            class="absolute right-0 z-30 mt-2 w-60 overflow-hidden rounded-xl border border-slate-100 bg-white py-1 shadow-lg"
          >
            <button
              class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-slate-700 transition hover:bg-slate-50"
              @click="openLinkDialog"
            >
              <svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-slate-500" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M10 13a5 5 0 007.54.54l3-3a5 5 0 00-7.07-7.07l-1.72 1.71" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M14 11a5 5 0 00-7.54-.54l-3 3a5 5 0 007.07 7.07l1.71-1.71" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span class="flex-1">新建链接任务</span>
              <span class="rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-400">Ctrl</span>
              <span class="rounded border border-slate-200 px-1.5 py-0.5 text-xs text-slate-400">N</span>
            </button>
            <button
              class="flex w-full items-center gap-3 px-4 py-2.5 text-left text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
              :disabled="torrentUploading"
              @click="pickTorrentFile"
            >
              <span class="flex h-4 w-4 shrink-0 items-center justify-center rounded border border-slate-300 text-[9px] font-bold text-slate-500">BT</span>
              <span class="flex-1">{{ torrentUploading ? '上传中…' : '新建 BT 任务' }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <p v-if="current && current.status === 'PARSING'" class="mb-4 text-sm text-primary">
      正在解析元数据（最长约 60 秒），请稍候…
    </p>
    <p v-if="parseError && !linkDialogOpen" class="mb-4 text-sm text-red-500">{{ parseError }}</p>

    <!-- 解析结果：文件勾选 -->
    <div v-if="files.length > 0" class="mb-6 rounded-card border border-slate-100 bg-white shadow-card">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4">
        <div class="min-w-0">
          <p class="truncate text-base font-semibold text-slate-800">{{ current.name }}</p>
          <p class="mt-0.5 text-xs text-slate-400">
            共 {{ files.length }} 个文件 · {{ formatSize(current.totalSize) }}
            <template v-if="engine && engine.savePath"> · 保存目录：{{ engine.savePath }}</template>
          </p>
        </div>
        <button class="btn-primary px-5 py-2 text-sm" :disabled="starting" @click="startDownload">
          {{ starting ? '创建中' : `开始下载（已选 ${selected.size} 个）` }}
        </button>
      </div>
      <div v-if="downloadError" class="flex flex-wrap items-center gap-3 px-5 pt-3 text-sm text-red-500">
        <span>{{ downloadError }}</span>
        <button
          v-if="downloadError.includes('需要重新解析')"
          class="font-medium text-primary hover:underline disabled:opacity-40"
          :disabled="submitting"
          @click="retryParseMetadata"
        >{{ submitting ? '重新解析中…' : '重新解析元数据' }}</button>
      </div>
      <div class="max-h-72 overflow-y-auto">
        <table class="min-w-full divide-y divide-slate-100 text-sm">
          <thead class="sticky top-0 bg-slate-50/95 backdrop-blur">
            <tr>
              <th class="w-10 px-4 py-2.5 text-left">
                <input
                  type="checkbox"
                  :checked="allSelected"
                  :indeterminate="selected.size > 0 && !allSelected"
                  @change="toggleAllFiles"
                />
              </th>
              <th class="px-4 py-2.5 text-left font-medium text-slate-500">文件</th>
              <th class="px-4 py-2.5 text-right font-medium text-slate-500">大小</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr
              v-for="node in visibleNodes"
              :key="node.type + ':' + node.path"
              class="transition hover:bg-slate-50/50"
            >
              <td class="px-4 py-2.5">
                <input
                  v-if="node.type === 'file'"
                  type="checkbox"
                  :checked="isSelected(node.index)"
                  @change="toggleFile(node.index)"
                />
                <input
                  v-else
                  type="checkbox"
                  :checked="dirState(node).checked"
                  :indeterminate="dirState(node).indeterminate"
                  @change="toggleDir(node)"
                />
              </td>
              <td class="px-4 py-2.5 text-slate-700">
                <div class="flex items-center gap-2" :style="{ paddingLeft: node.depth * 18 + 'px' }">
                  <button
                    v-if="node.type === 'dir' && node.children.length"
                    type="button"
                    class="flex h-4 w-4 shrink-0 items-center justify-center text-slate-400 transition hover:text-slate-600"
                    :aria-label="isDirExpanded(node.path) ? '收起' : '展开'"
                    @click="toggleDirExpand(node)"
                  >
                    <svg
                      viewBox="0 0 24 24"
                      class="h-3.5 w-3.5 transition-transform"
                      :class="isDirExpanded(node.path) ? 'rotate-90' : ''"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2.5"
                    >
                      <path d="M9 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                  </button>
                  <span v-else class="w-4 shrink-0"></span>
                  <svg v-if="node.type === 'dir'" viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-primary" fill="currentColor">
                    <path d="M10 4H4a2 2 0 00-2 2v12a2 2 0 002 2h16a2 2 0 002-2V8a2 2 0 00-2-2h-8l-2-2z" />
                  </svg>
                  <svg v-else viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-slate-400" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M6 2h7l5 5v13a2 2 0 01-2 2H6a2 2 0 01-2-2V4a2 2 0 012-2z" />
                    <path d="M13 2v5h5" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <span class="min-w-0 truncate" :title="node.path" :class="node.type === 'dir' ? 'font-medium' : ''">{{ node.name }}</span>
                  <span v-if="node.type === 'dir'" class="shrink-0 text-xs text-slate-400">{{ node.fileCount }} 个文件</span>
                </div>
              </td>
              <td class="whitespace-nowrap px-4 py-2.5 text-right text-slate-500">{{ formatSize(node.size) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 下载管理 -->
    <div class="mb-6">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-base font-semibold text-slate-800">下载管理</h2>
        <div class="flex items-center gap-2">
          <select
            v-model="downloadsStatus"
            class="rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-sm text-slate-600 outline-none focus:border-primary"
          >
            <option value="">全部状态</option>
            <option value="downloading">下载中</option>
            <option value="completed">已完成</option>
          </select>
          <input
            v-model="downloadsKeyword"
            type="text"
            placeholder="按名称模糊搜索"
            class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
            @keydown.enter="searchDownloads"
          />
          <button class="btn-primary px-4 py-1.5 text-sm" @click="searchDownloads">查询</button>
        </div>
      </div>
      <p v-if="downloadsError" class="mb-3 text-sm text-red-500">{{ downloadsError }}</p>
      <div class="overflow-x-auto rounded-card border border-slate-100 bg-white shadow-card">
        <table class="min-w-full divide-y divide-slate-100 text-sm">
          <thead class="bg-slate-50/60">
            <tr>
              <th class="px-4 py-3 text-left font-medium text-slate-500">名称</th>
              <th class="w-56 px-4 py-3 text-left font-medium text-slate-500">进度</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">速度</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">剩余时间</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">状态</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">保存路径</th>
              <th class="px-4 py-3 text-right font-medium text-slate-500">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-if="filteredDownloads.length === 0">
              <td colspan="7" class="px-4 py-10 text-center text-slate-400">{{ downloadsQuery || downloadsStatus ? '没有匹配的下载任务' : '暂无下载任务' }}</td>
            </tr>
            <template v-for="item in pagedDownloads" :key="item.infoHash">
              <tr class="transition hover:bg-slate-50/50">
                <td class="max-w-48 truncate px-4 py-3 font-medium text-slate-700" :title="item.name">{{ item.name }}</td>
                <td class="px-4 py-3">
                  <div class="h-1.5 w-full overflow-hidden rounded-full bg-slate-100">
                    <div class="h-full rounded-full bg-primary transition-all" :style="{ width: item.progressPercent + '%' }"></div>
                  </div>
                  <div class="mt-1 text-xs text-slate-400">
                    {{ item.progressPercent }}% · {{ formatSize(item.downloadedBytes) }} / {{ formatSize(item.totalBytes) }}
                  </div>
                </td>
                <td class="whitespace-nowrap px-4 py-3 text-slate-500">{{ formatSpeed(item.downloadSpeedBytesPerSec) }}</td>
                <td class="whitespace-nowrap px-4 py-3 text-slate-500">{{ formatEta(item.etaSeconds) }}</td>
                <td class="px-4 py-3">
                  <span class="inline-flex rounded-full px-2 py-0.5 text-xs font-medium" :class="stateClass(item.state)">
                    {{ stateLabel(item.state) }}
                  </span>
                </td>
                <td class="max-w-40 truncate px-4 py-3 text-slate-400" :title="item.savePath">{{ item.savePath }}</td>
                <td class="whitespace-nowrap px-4 py-3 text-right">
                  <button
                    v-if="DONE_STATES.includes(item.state)"
                    class="text-primary hover:underline"
                    @click="toggleExpand(item.infoHash)"
                  >{{ isExpanded(item.infoHash) ? '收起' : '文件' }}</button>
                  <button v-if="PAUSED_STATES.includes(item.state)" class="ml-3 text-primary hover:underline" @click="resumeOne(item)">继续</button>
                  <button v-else class="ml-3 text-primary hover:underline" @click="pauseOne(item)">暂停</button>
                  <button class="ml-3 text-red-500 hover:underline" @click="openDelete(item)">删除</button>
                </td>
              </tr>
              <!-- 已完成文件列表（展开时显示） -->
              <tr v-if="isExpanded(item.infoHash)">
                <td colspan="7" class="bg-slate-50/50 px-4 py-3">
                  <div class="rounded-lg border border-slate-200 bg-white">
                    <div class="border-b border-slate-100 px-4 py-2 text-xs font-medium text-slate-500">
                      已完成文件（点击文件名下载到浏览器）
                    </div>
                    <div class="max-h-48 overflow-y-auto">
                      <table class="min-w-full text-sm">
                        <tbody>
                          <tr
                            v-for="file in completedFilesMap[item.infoHash] || []"
                            :key="file.index"
                            class="border-b border-slate-50 last:border-0 hover:bg-slate-50/50"
                          >
                            <td class="px-4 py-2">
                              <button
                                class="text-primary hover:underline"
                                @click="browserDownload(item, file)"
                              >{{ file.path }}</button>
                            </td>
                            <td class="whitespace-nowrap px-4 py-2 text-right text-slate-500">{{ formatSize(file.size) }}</td>
                          </tr>
                          <tr v-if="!completedFilesMap[item.infoHash] || completedFilesMap[item.infoHash].length === 0">
                            <td colspan="2" class="px-4 py-4 text-center text-slate-400">暂无已完成文件</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <!-- 分页 -->
      <div class="mt-3 flex flex-wrap items-center justify-between gap-3 text-sm text-slate-500">
        <div class="flex items-center gap-2">
          <span>共 {{ filteredDownloads.length }} 条</span>
          <select
            v-model="downloadsSize"
            class="rounded-lg border border-slate-200 bg-white px-2 py-1.5 outline-none focus:border-primary"
          >
            <option :value="10">10 条/页</option>
            <option :value="30">30 条/页</option>
          </select>
        </div>
        <div class="flex items-center gap-1">
          <button
            class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
            :disabled="downloadsPage <= 1"
            @click="downloadsPage--"
          >上一页</button>
          <span class="px-2">{{ downloadsPage }} / {{ downloadsTotalPages }}</span>
          <button
            class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
            :disabled="downloadsPage >= downloadsTotalPages"
            @click="downloadsPage++"
          >下一页</button>
        </div>
      </div>
    </div>

    <!-- 解析历史 -->
    <div>
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-base font-semibold text-slate-800">解析历史</h2>
        <div class="flex items-center gap-2">
          <input
            v-model="historyKeyword"
            type="text"
            placeholder="按名称模糊搜索"
            class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
            @keydown.enter="searchHistory"
          />
          <button class="btn-primary px-4 py-1.5 text-sm" :disabled="historyLoading" @click="searchHistory">查询</button>
        </div>
      </div>
      <div class="overflow-x-auto rounded-card border border-slate-100 bg-white shadow-card">
        <table class="min-w-full divide-y divide-slate-100 text-sm">
          <thead class="bg-slate-50/60">
            <tr>
              <th class="px-4 py-3 text-left font-medium text-slate-500">名称</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">总大小</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">状态</th>
              <th class="px-4 py-3 text-left font-medium text-slate-500">解析时间</th>
              <th class="px-4 py-3 text-right font-medium text-slate-500">操作</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50">
            <tr v-if="historyList.length === 0 && !historyLoading">
              <td colspan="5" class="px-4 py-10 text-center text-slate-400">暂无解析历史</td>
            </tr>
            <tr v-for="item in historyList" :key="item.id" class="transition hover:bg-slate-50/50">
              <td class="max-w-56 truncate px-4 py-3 font-medium text-slate-700" :title="item.name || item.infoHash">
                {{ item.name || item.infoHash }}
              </td>
              <td class="whitespace-nowrap px-4 py-3 text-slate-500">{{ formatSize(item.totalSize) }}</td>
              <td class="px-4 py-3">
                <span
                  class="inline-flex rounded-full px-2 py-0.5 text-xs font-medium"
                  :class="item.status === 'SUCCEEDED' ? 'bg-green-50 text-green-600' : item.status === 'PARSING' ? 'bg-primary-light text-primary' : 'bg-red-50 text-red-500'"
                >{{ item.status === 'SUCCEEDED' ? '解析成功' : item.status === 'PARSING' ? '解析中' : '解析失败' }}</span>
              </td>
              <td class="whitespace-nowrap px-4 py-3 text-slate-400">{{ formatDate(item.createdAt) }}</td>
              <td class="whitespace-nowrap px-4 py-3 text-right">
                <button v-if="item.status === 'SUCCEEDED'" class="text-primary hover:underline" @click="openHistory(item)">打开</button>
                <button class="ml-3 text-red-500 hover:underline" @click="removeHistory(item)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <!-- 分页 -->
      <div class="mt-3 flex flex-wrap items-center justify-between gap-3 text-sm text-slate-500">
        <div class="flex items-center gap-2">
          <span>共 {{ historyTotal }} 条</span>
          <select
            v-model="historySize"
            class="rounded-lg border border-slate-200 bg-white px-2 py-1.5 outline-none focus:border-primary"
            @change="historyPage = 1"
          >
            <option :value="10">10 条/页</option>
            <option :value="30">30 条/页</option>
          </select>
        </div>
        <div class="flex items-center gap-1">
          <button
            class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
            :disabled="historyPage <= 1"
            @click="historyPage--"
          >上一页</button>
          <span class="px-2">{{ historyPage }} / {{ historyTotalPages }}</span>
          <button
            class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
            :disabled="historyPage >= historyTotalPages"
            @click="historyPage++"
          >下一页</button>
        </div>
      </div>
    </div>

    <!-- 新建链接任务弹窗 -->
    <div
      v-if="linkDialogOpen"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4"
      @click.self="closeLinkDialog"
    >
      <div class="w-full max-w-lg rounded-card border border-slate-100 bg-white p-6 shadow-card">
        <h3 class="mb-4 text-base font-semibold text-slate-800">新建链接任务</h3>
        <input
          v-model="magnetUrl"
          type="text"
          placeholder="粘贴磁力链接（magnet:?xt=...）"
          class="w-full rounded-lg border border-slate-200 px-3 py-2.5 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
          @keydown.enter="submitParse"
        />
        <p v-if="parseError" class="mt-2 text-sm text-red-500">{{ parseError }}</p>
        <div class="mt-5 flex justify-end gap-2">
          <button class="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 transition hover:bg-slate-50" @click="closeLinkDialog">取消</button>
          <button class="btn-primary px-5 py-2 text-sm" :disabled="submitting" @click="submitParse">
            {{ submitting ? '提交中' : '解析' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除任务弹窗：默认保留文件，勾选后连同文件一起删除 -->
    <div v-if="deleting" class="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" @click.self="deleting = null">
      <div class="w-full max-w-md rounded-card border border-slate-100 bg-white p-6 shadow-card">
        <h3 class="mb-2 text-base font-semibold text-slate-800">删除下载任务</h3>
        <p class="mb-4 truncate text-sm text-slate-500" :title="deleting.name">{{ deleting.name }}</p>
        <label class="mb-5 flex items-center gap-2 text-sm text-slate-600">
          <input v-model="deleteFiles" type="checkbox" />
          同时删除已下载的文件
        </label>
        <div class="flex justify-end gap-2">
          <button class="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 transition hover:bg-slate-50" @click="deleting = null">取消</button>
          <button
            class="rounded-lg px-4 py-2 text-sm text-white transition"
            :class="deleteFiles ? 'bg-red-500 hover:bg-red-600' : 'bg-primary hover:opacity-90'"
            @click="confirmDelete"
          >
            {{ deleteFiles ? '删除任务及文件' : '仅删除任务' }}
          </button>
        </div>
      </div>
    </div>
  </section>
</template>
