<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { isLoggedIn, authModal } from '../stores/auth'
import {
  nativeHealth, inspectNativeTorrent, createNativeTask, listNativeTasks,
  getNativeTask, prepareNativeTask, pauseNativeTask, deleteNativeTask,
  runNativeRound, runNativeContinuous, latestNativeTaskJob, nativeJobStatus, nativeJobMetrics,
  cancelNativeJob, recoverOrphanNativeTask,
  startNativeMetadata, nativeMetadataStatus, nativeMetadataFiles, cancelNativeMetadata,
  createNativeFromMetadata, nativeOwnerQueueHealth,
  nativeDownloadRoot, browseNativeDownloadRoots, saveNativeDownloadRoot, clearNativeDownloadRoot
} from '../api/nativeTorrent'

const magnetInput = ref('')
const discoveryConsent = ref(false)
const metadataJob = ref(null)
const metadataFiles = ref([])
const metadataSelected = ref(new Set())
const metadataPage = ref(1)
const metadataPageSize = 30
const metadataTotalFiles = ref(0)
const metadataPageBusy = ref(false)
const metadataLoaded = ref(false)
let metadataRequestSeq = 0
const metadataBusy = ref(false)
const metadataEnabled = computed(() => isLoggedIn.value && !!health.value?.metadataLookupEnabled)

async function submitMetadata() {
  const uri = magnetInput.value.trim()
  if (!metadataEnabled.value || metadataBusy.value) return
  if (!/^magnet:\?xt=urn:btih:/i.test(uri)) {
    error.value = '请输入有效的 v1 Magnet 链接'
    return
  }
  if (!discoveryConsent.value) {
    error.value = '请先确认允许公开 Peer、Tracker 和 DHT 发现'
    return
  }
  metadataBusy.value = true
  error.value = ''
  metadataRequestSeq++
  metadataFiles.value = []
  metadataSelected.value = new Set()
  metadataPage.value = 1
  metadataTotalFiles.value = 0
  metadataLoaded.value = false
  try {
    const response = await startNativeMetadata(uri, true)
    metadataJob.value = response.status
  } catch (e) { error.value = e.message || '元数据任务提交失败' }
  finally { metadataBusy.value = false }
}

async function loadMetadataPage(targetPage) {
  const jobId = metadataJob.value?.jobId
  if (!jobId || metadataPageBusy.value || targetPage < 1 ||
      (metadataTotalFiles.value > 0 &&
       (targetPage - 1) * metadataPageSize >= metadataTotalFiles.value)) return
  const requestId = ++metadataRequestSeq
  metadataPageBusy.value = true
  try {
    const data = await nativeMetadataFiles(jobId, targetPage, metadataPageSize)
    if (disposed || !isLoggedIn.value || metadataJob.value?.jobId !== jobId ||
        requestId !== metadataRequestSeq) return
    metadataFiles.value = data.files || []
    metadataTotalFiles.value = data.totalFiles || 0
    metadataPage.value = targetPage
    if (!metadataLoaded.value) {
      metadataSelected.value = new Set(metadataFiles.value.map(file => file.index))
      metadataLoaded.value = true
    }
  } catch (e) {
    if (!disposed && requestId === metadataRequestSeq)
      error.value = e.message || '文件分页查询失败'
  } finally {
    if (requestId === metadataRequestSeq) metadataPageBusy.value = false
  }
}

async function updateMetadata() {
  if (!metadataJob.value?.jobId || disposed || !isLoggedIn.value) return
  const jobId = metadataJob.value.jobId
  try {
    const snapshot = await nativeMetadataStatus(jobId)
    if (disposed || metadataJob.value?.jobId !== jobId) return
    metadataJob.value = snapshot
    if (snapshot.state === 'SUCCEEDED' && !metadataLoaded.value && !metadataPageBusy.value)
      await loadMetadataPage(1)
  } catch(e) { if (!disposed) error.value = e.message || '元数据状态查询失败' }
}

function toggleMetadataFile(index) {
  const next = new Set(metadataSelected.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  metadataSelected.value = next
}

async function createFromMetadata() {
  if (!canCreate.value || metadataJob.value?.state !== 'SUCCEEDED' ||
      !metadataSelected.value.size || metadataBusy.value) return
  metadataBusy.value = true
  error.value = ''
  try {
    const row = await createNativeFromMetadata(metadataJob.value.jobId,
      [...metadataSelected.value].sort((a,b) => a-b))
    message.value = `已根据 Magnet 元数据创建任务 ${shortId(row.taskUuid)}，请手动启动下载`
    page.value = 1
    await refresh()
  } catch (e) { error.value = e.message || '无法通过已验证元数据创建任务' }
  finally { metadataBusy.value = false }
}

async function stopMetadata() {
  if (!metadataJob.value?.jobId || metadataBusy.value) return
  metadataBusy.value = true
  try { metadataJob.value = await cancelNativeMetadata(metadataJob.value.jobId) }
  catch(e) { error.value = e.message || '取消解析失败' }
  finally { metadataBusy.value = false }
}

const health = ref(null)
const tasks = ref([])
const ownerQueue = ref(null)
async function refreshOwnerQueue() {
  if (!canRun.value || disposed) { ownerQueue.value = null; return }
  try { ownerQueue.value = await nativeOwnerQueueHealth() }
  catch { ownerQueue.value = null }
}
async function loadDownloadRoot() {
  if (!canSelectRoot.value || disposed) { downloadRoot.value = null; return }
  try { downloadRoot.value = await nativeDownloadRoot() }
  catch (e) { if (!disposed) error.value = e.message || '下载目录信息加载失败' }
}
async function openRootBrowser(path = '') {
  if (!canSelectRoot.value || rootBusy.value) return
  rootBusy.value = true
  error.value = ''
  try {
    rootBrowse.value = await browseNativeDownloadRoots(path)
    rootModal.value = true
  } catch (e) { error.value = e.message || '目录读取失败' }
  finally { rootBusy.value = false }
}
async function chooseRoot() {
  if (!canSelectRoot.value || rootBusy.value || !rootBrowse.value?.path) return
  rootBusy.value = true
  error.value = ''
  try {
    const saved = await saveNativeDownloadRoot(rootBrowse.value.path)
    if (downloadRoot.value) downloadRoot.value = { ...downloadRoot.value, rootPath: saved.rootPath }
    rootModal.value = false
    message.value = `下载保存目录已更新为 ${saved.rootPath}`
  } catch (e) { error.value = e.message || '保存下载目录失败' }
  finally { rootBusy.value = false }
}
async function restoreDefaultRoot() {
  if (!canSelectRoot.value || rootBusy.value) return
  rootBusy.value = true
  error.value = ''
  try {
    await clearNativeDownloadRoot()
    if (downloadRoot.value) downloadRoot.value = { ...downloadRoot.value, rootPath: null }
    message.value = '已恢复使用服务器默认下载目录'
  } catch (e) { error.value = e.message || '恢复默认目录失败' }
  finally { rootBusy.value = false }
}
const inspected = ref(null)
const torrentFile = ref(null)
const selected = ref(new Set())
const loading = ref(false)
const busyId = ref('')
const message = ref('')
const error = ref('')
const page = ref(1)
const pageSize = 20
const jobs = reactive({})
const metrics = reactive({})
const lookupState = reactive({})
// Continuous high-performance Piece rounds
const continuous = ref(true)
// Long-lived bounded session: the server still enforces user/queue/fence limits.
const piecesPerRound = ref(128)
const maxRounds = ref(10000)
const maxSeconds = ref(86400)
const autoContinuous = ref(true)
const continuousTasks = reactive(new Set())
let timer = null
let disposed = false
let polling = false
const lookedUpJobs = new Set()

const canManage = computed(() => isLoggedIn.value && !!health.value?.taskControlEnabled)
const canCreate = computed(() => canManage.value && !!health.value?.taskCreationEnabled)
const canRun = computed(() => canManage.value && !!health.value?.configuredEnabled &&
  !!health.value?.workerRoundEnabled && !!health.value?.asyncWorkerEnabled)
const canContinuous = computed(() => canRun.value && !!health.value?.continuousWorkerEnabled)
const orphanRecoveryEnabled = computed(() => canRun.value && !!health.value?.singleInstanceOrphanRecoveryEnabled)
const downloadRoot = ref(null)
const rootModal = ref(false)
const rootBrowse = ref(null)
const rootBusy = ref(false)
const canSelectRoot = computed(() => canCreate.value && !!health.value?.downloadRootSelectionEnabled)
const activeJob = task => {
  const job = jobs[task.taskUuid]
  return job && ['QUEUED', 'RUNNING'].includes(job.state) ? job : null
}
const suspectedOrphan = task => task.state === 'RUNNING' &&
  lookupState[task.taskUuid] === 'absent' && !activeJob(task)
const nbytes = value => {
  const n = Number(value || 0)
  if (!Number.isFinite(n) || n < 0) return '0 B'
  if (n < 1024) return `${n} B`
  const exp = Math.min(4, Math.floor(Math.log(n) / Math.log(1024)))
  return `${(n / 1024 ** exp).toFixed(2)} ${['B', 'KiB', 'MiB', 'GiB', 'TiB'][exp]}`
}
const percent = value => Math.max(0, Math.min(100, Number(value || 0))).toFixed(1)
const displayVerified = task => {
  const m = metrics[task.taskUuid]
  if (m && m.verifiedBytes != null && m.verifiedBytes > (task.verifiedBytes || 0)) {
    return m.verifiedBytes
  }
  return task.verifiedBytes || 0
}
const displayPercent = task => {
  const v = displayVerified(task)
  const total = task.selectedBytes || 1
  return Math.max(0, Math.min(100, (v / total) * 100)).toFixed(1)
}
const displaySpeed = task => {
  const m = metrics[task.taskUuid]
  return m ? nbytes(m.recentBytesPerSecond) : '0 B'
}
const shortId = uuid => uuid ? `${uuid.slice(0, 8)}…${uuid.slice(-6)}` : ''
const metadataStatusText = {
  QUEUED: '等待解析', RUNNING: '正在发现 Peer / 获取元数据',
  SUCCEEDED: '解析成功', FAILED: '解析失败',
  CANCELLED: '已取消', TIMED_OUT: '解析超时'
}
const statusText = {
  CREATED: '已创建', READY: '待执行', RUNNING: '下载中',
  PAUSED: '已暂停', COMPLETED: '已完成', FAILED: '失败',
  QUEUED: '排队中', SUCCEEDED: '本轮完成', PARTIAL: '本轮部分完成',
  CANCELLED: '已取消'
}
function selection(index) {
  const next = new Set(selected.value)
  if (next.has(index)) next.delete(index)
  else next.add(index)
  selected.value = next
}
function toggleAll() {
  if (!inspected.value) return
  selected.value = selected.value.size === inspected.value.files.length
    ? new Set()
    : new Set(inspected.value.files.map(file => file.index))
}
async function refresh() {
  if (!canManage.value || polling || disposed) return
  polling = true
  try {
    const rows = await listNativeTasks(page.value, pageSize)
    if (!disposed) tasks.value = rows
    // A tab refresh loses job UUIDs, but the single-JVM job registry may still
    // own an active Worker. Re-bind it before showing a pause control.
    for (const row of rows) {
      if (disposed) break
      const uuid = row.taskUuid
      if (!['READY', 'RUNNING'].includes(row.state)) {
        delete lookupState[uuid]
        continue
      }
      if (activeJob(row) || lookedUpJobs.has(uuid)) continue
      lookedUpJobs.add(uuid)
      try {
        const currentJob = await latestNativeTaskJob(uuid)
        if (!disposed) {
          if (currentJob) jobs[uuid] = currentJob
          lookupState[uuid] = currentJob && ['QUEUED', 'RUNNING'].includes(currentJob.state)
            ? 'active' : 'absent'
          // A missing (or finished) job is NOT a permanent negative cache:
          // another browser tab may enqueue a Worker immediately afterwards.
          if (lookupState[uuid] !== 'active') lookedUpJobs.delete(uuid)
        }
      } catch (e) {
        lookedUpJobs.delete(uuid)
        if (!disposed) {
          lookupState[uuid] = 'error'
          error.value = e.message || '无法确认任务作业状态，请稍后重试'
        }
      }
    }
    const active = Object.entries(jobs).filter(([, job]) => ['QUEUED', 'RUNNING'].includes(job.state))
    for (const [taskId, job] of active) {
      try {
        const [next, detail] = await Promise.all([
          nativeJobStatus(job.jobId),
          nativeJobMetrics(job.jobId)
        ])
        if (!disposed) {
          jobs[taskId] = next
          metrics[taskId] = detail
          if (detail && detail.verifiedBytes != null) {
            const current = tasks.value.find(row => row.taskUuid === taskId)
            if (current && detail.verifiedBytes > current.verifiedBytes) {
              current.verifiedBytes = detail.verifiedBytes
              current.progressPercent = (detail.verifiedBytes / (current.selectedBytes || 1)) * 100
            }
          }
          if (next.terminal) {
            lookedUpJobs.delete(taskId)
            const latest = await getNativeTask(taskId)
            if (!disposed) tasks.value = tasks.value.map(row => row.taskUuid === taskId ? latest : row)
            if (autoContinuous.value && continuousTasks.has(taskId) && latest.state !== 'COMPLETED' && !busyId.value) {
              setTimeout(async () => {
                if (!disposed && continuousTasks.has(taskId) && !busyId.value) {
                  try {
                    await updateTask(latest, 'start')
                  } catch (ignored) {}
                }
              }, 1200)
            }
          }
        }
      } catch (e) {
        // A server restart destroys the in-memory registry, not the persisted task.
        // Force a fresh owner-scoped lookup before enabling any recovery action.
        if (!disposed) {
          lookupState[taskId] = 'error'
          lookedUpJobs.delete(taskId)
          delete jobs[taskId]
          delete metrics[taskId]
          error.value = e.message || '作业状态失效，请刷新并确认数据库状态'
        }
      }
    }
  } catch (e) {
    if (!disposed) error.value = e.message || '任务列表加载失败'
  } finally { polling = false }
}
async function initialize() {
  if (!isLoggedIn.value) {
    lookedUpJobs.clear()
    for (const uuid of Object.keys(lookupState)) delete lookupState[uuid]
    health.value = null
    tasks.value = []
    ownerQueue.value = null
    downloadRoot.value = null
    rootModal.value = false
    rootBrowse.value = null
    metadataRequestSeq++
    metadataJob.value = null
    metadataFiles.value = []
    metadataSelected.value = new Set()
    metadataLoaded.value = false
    metadataPageBusy.value = false
    metadataTotalFiles.value = 0
    metadataPage.value = 1
    return
  }
  loading.value = true
  error.value = ''
  try {
    health.value = await nativeHealth()
    if (health.value?.taskControlEnabled) await refresh()
    await refreshOwnerQueue()
    await loadDownloadRoot()
  } catch (e) { error.value = e.message || '原生下载服务不可用' }
  finally { loading.value = false }
}
async function inspectFile(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!/\.torrent$/i.test(file.name)) {
    error.value = '请上传 .torrent 文件'
    return
  }
  loading.value = true
  error.value = ''
  message.value = ''
  inspected.value = null
  selected.value = new Set()
  try {
    const data = await inspectNativeTorrent(file)
    if (disposed) return
    torrentFile.value = file
    inspected.value = data
    selected.value = new Set(data.files.map(row => row.index).slice(0, 1000))
  } catch (e) {error.value = e.message || '种子校验失败'}
  finally {loading.value = false}
}
async function create() {
  if (!canCreate.value || loading.value || !torrentFile.value || selected.value.size === 0) return
  loading.value = true
  error.value = ''
  try {
    const row = await createNativeTask(torrentFile.value, [...selected.value].sort((a,b)=>a-b))
    message.value = `已创建任务 ${shortId(row.taskUuid)}，请手动点击启动下载`
    torrentFile.value = null
    inspected.value = null
    selected.value = new Set()
    page.value = 1
    await refresh()
  } catch (e) {error.value = e.message || '创建失败'}
  finally {loading.value = false}
}
async function updateTask(task, action) {
  if (!canManage.value || busyId.value) return
  const uuid = task.taskUuid
  busyId.value = uuid
  error.value = ''
  message.value = ''
  try {
    let latest = await getNativeTask(uuid)
    if (['recover', 'delete'].includes(action) && latest.revision !== task.revision) {
      tasks.value = tasks.value.map(t => t.taskUuid === uuid ? latest : t)
      throw new Error('任务状态已变化，请确认后重新操作')
    }
    if (action === 'start') {
      if (!canRun.value) throw new Error('服务器没有启用实验性下载 Worker')
      // 若当前任务刚执行暂停，可能还在短暂从 RUNNING 收敛为 PAUSED，短时平滑等待
      for (let i = 0; i < 15 && latest.state === 'RUNNING'; i++) {
        await new Promise(r => setTimeout(r, 200))
        latest = await getNativeTask(uuid)
      }
      // Always recheck the current worker before enqueueing; another tab may have submitted it.
      const existingJob = await latestNativeTaskJob(uuid)
      if (existingJob && ['QUEUED', 'RUNNING'].includes(existingJob.state)) {
        jobs[uuid] = existingJob
        lookupState[uuid] = 'active'
        throw new Error('此任务已有正在运行的作业')
      }
      lookupState[uuid] = 'absent'
      if (activeJob(task)) throw new Error('此任务已有正在运行的作业')
      let ready = latest
      if (['CREATED', 'PAUSED', 'FAILED'].includes(ready.state)) {
        ready = await prepareNativeTask(uuid, ready.revision)
      } else if (ready.state !== 'READY') {
        throw new Error('当前任务不可启动')
      }
      // 失败的队列提交可能保留 READY 状态，刷新后可安全重试。
      const accepted = continuous.value && canContinuous.value
        ? await runNativeContinuous(uuid, ready.revision,
          Number(piecesPerRound.value), Number(maxRounds.value), Number(maxSeconds.value))
        : await runNativeRound(uuid, ready.revision, Math.min(4, Number(piecesPerRound.value)))
      jobs[uuid] = accepted.status
      lookupState[uuid] = 'active'
      if (continuous.value && autoContinuous.value) continuousTasks.add(uuid)
      message.value = `已提交极速下载作业 ${shortId(accepted.jobId)}`
    } else if (action === 'pause') {
      continuousTasks.delete(uuid)
      const active = activeJob(task)
      if (active) {
        jobs[uuid] = await cancelNativeJob(active.jobId)
      } else if (['READY', 'RUNNING'].includes(latest.state)) {
        // READY is not running network I/O; RUNNING with no local job entry uses pause endpoint
        await pauseNativeTask(uuid, latest.revision)
      } else {
        throw new Error('当前任务不需要暂停')
      }
      // 确保底层 Worker 彻底退出且任务状态落地为 PAUSED
      let updated = await getNativeTask(uuid)
      for (let i = 0; i < 15 && updated.state === 'RUNNING'; i++) {
        await new Promise(r => setTimeout(r, 200))
        updated = await getNativeTask(uuid)
      }
      tasks.value = tasks.value.map(t => t.taskUuid === uuid ? updated : t)
      message.value = '暂停操作已提交，已验证的文件块会保留'
    } else if (action === 'recover') {
      continuousTasks.delete(uuid)
      if (!orphanRecoveryEnabled.value || !suspectedOrphan(latest))
        throw new Error('当前任务不符合单实例孤儿恢复条件')
      // Re-check the local registry at the exact moment the user chooses to recover.
      const currentJob = await latestNativeTaskJob(uuid)
      if (currentJob && ['QUEUED', 'RUNNING'].includes(currentJob.state)) {
        jobs[uuid] = currentJob
        lookupState[uuid] = 'active'
        throw new Error('检测到仍在运行的 Worker，禁止回收')
      }
      if (!window.confirm('仅在已确认单实例部署、无其他服务器正在下载该任务时执行恢复。确定将当前 RUNNING记录标记为 PAUSED 吗？')) return
      await recoverOrphanNativeTask(uuid, latest.revision)
      lookupState[uuid] = 'absent'
      message.value = '已将孤儿任务恢复到暂停状态，可以手动启动下载'
    } else if (action === 'delete') {
      continuousTasks.delete(uuid)
      if (activeJob(task) || latest.state === 'RUNNING')
        throw new Error('请先停止当前 Worker，再删除任务')
      if (!window.confirm('确定删除任务记录吗？此操作不会删除已写入的文件。')) return
      await deleteNativeTask(uuid, latest.revision)
      delete jobs[uuid]
      delete metrics[uuid]
      delete lookupState[uuid]
      lookedUpJobs.delete(uuid)
      message.value = '已删除任务记录，磁盘文件未删除'
    }
    if (action === 'delete') {
      tasks.value = tasks.value.filter(t => t.taskUuid !== uuid)
    } else if (action !== 'pause') {
      const updated = await getNativeTask(uuid)
      tasks.value = tasks.value.map(t => t.taskUuid === uuid ? updated : t)
    }
    if (action === 'start') await refresh()
  } catch (e) {error.value = e.message || '操作失败'}
  finally {busyId.value = ''}
}
async function restartTask(task) {
  if (!canRun.value || busyId.value) return
  const uuid = task.taskUuid
  busyId.value = uuid
  error.value = ''
  message.value = `正在停止当前作业并以每轮 ${piecesPerRound.value} 块重启…`
  try {
    continuousTasks.delete(uuid)
    const active = activeJob(task)
    if (active) {
      await cancelNativeJob(active.jobId)
      delete jobs[uuid]
    }
    let current = await getNativeTask(uuid)
    for (let i = 0; i < 20 && current.state === 'RUNNING'; i++) {
      await new Promise(r => setTimeout(r, 400))
      current = await getNativeTask(uuid)
    }
    if (['CREATED', 'PAUSED', 'FAILED'].includes(current.state)) {
      current = await prepareNativeTask(uuid, current.revision)
    }
    if (current.state !== 'READY') {
      throw new Error(`任务状态未能重置为待执行（当前状态：${current.state}），请手动点击“启动极速下载”`)
    }
    const accepted = continuous.value && canContinuous.value
      ? await runNativeContinuous(uuid, current.revision,
        Number(piecesPerRound.value), Number(maxRounds.value), Number(maxSeconds.value))
      : await runNativeRound(uuid, current.revision, Math.min(4, Number(piecesPerRound.value)))
    jobs[uuid] = accepted.status
    lookupState[uuid] = 'active'
    if (continuous.value && autoContinuous.value) continuousTasks.add(uuid)
    message.value = `已成功以每轮 ${piecesPerRound.value} 块重启极速下载作业 ${shortId(accepted.jobId)}`
    await refresh()
  } catch (e) {
    error.value = e.message || '重启任务失败'
  } finally {
    busyId.value = ''
  }
}
async function turnPage(direction) {
  if (page.value + direction < 1 || busyId.value) return
  page.value += direction
  await refresh()
}
watch(isLoggedIn, () => {
  if (!isLoggedIn.value) {
    for (const k of Object.keys(jobs)) delete jobs[k]
    for (const k of Object.keys(metrics)) delete metrics[k]
    for (const k of Object.keys(lookupState)) delete lookupState[k]
    lookedUpJobs.clear()
    inspected.value = null
    torrentFile.value = null
    selected.value = new Set()
  }
  initialize()
})
onMounted(() => {
  disposed = false
  initialize()
  timer = setInterval(() => {
    if (metadataJob.value && ['QUEUED','RUNNING'].includes(metadataJob.value.state)) updateMetadata()
    if (canRun.value) refreshOwnerQueue()
    if (canManage.value && (tasks.value.some(t => t.state === 'RUNNING') ||
      Object.values(jobs).some(j => ['RUNNING', 'QUEUED'].includes(j.state)))) refresh()
  }, 2500)
})
onUnmounted(() => {
  disposed = true
  if (timer) clearInterval(timer)
  timer = null
})
</script>

<template>
  <main class="mx-auto max-w-6xl px-4 py-8 sm:px-6">
    <div class="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 class="text-2xl font-semibold text-slate-800">原生 BT 下载</h1>
        <p class="mt-1 text-sm text-slate-500">实验性 Native 引擎 · 只支持公开 v1 种子 · 不影响现有 qBittorrent 下载</p>
      </div>
      <button class="btn-ghost" :disabled="loading" @click="initialize">刷新服务状态</button>
    </div>

    <div v-if="!isLoggedIn" class="rounded-card border border-slate-100 bg-white p-8 text-center shadow-card">
      <p class="mb-4 text-slate-600">请先登录后管理自己的原生下载任务。</p>
      <button class="btn-primary" @click="authModal.open('login')">登录</button>
    </div>
    <template v-else>
      <p v-if="error" role="alert" class="mb-4 rounded-lg bg-red-50 p-3 text-sm text-red-600">{{ error }}</p>
      <p v-if="message" role="status" class="mb-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">{{ message }}</p>
      <div v-if="!canManage" class="mb-6 rounded-card border border-amber-200 bg-amber-50 p-5 text-sm text-amber-800">
        当前服务器尚未开启原生任务管理，请继续使用“磁力下载”中的 qBittorrent 功能。
      </div>

      <section class="mb-6 rounded-card border border-slate-100 bg-white p-5 shadow-card">
        <h2 class="text-lg font-semibold text-slate-800">Magnet 元数据发现（实验）</h2>
        <p class="mt-1 text-xs text-slate-500">这一步只尝试发现文件列表，不自动创建下载任务。公开发现可能向外部 Peer / Tracker / DHT 披露 InfoHash。</p>
        <form class="mt-4 space-y-3" @submit.prevent="submitMetadata">
          <textarea v-model="magnetInput" rows="2" maxlength="16384" placeholder="magnet:?xt=urn:btih:..." class="w-full rounded-lg border border-slate-200 p-3 text-sm" :disabled="!metadataEnabled || metadataBusy"></textarea>
          <label class="flex items-start gap-2 text-xs text-slate-600">
            <input v-model="discoveryConsent" type="checkbox" :disabled="!metadataEnabled || metadataBusy">
            我确认该资源允许公网发现，同意公开查询此 InfoHash
          </label>
          <div class="flex flex-wrap items-center gap-3">
            <button class="btn-primary text-sm" type="submit" :disabled="!metadataEnabled || !discoveryConsent || metadataBusy || !magnetInput.trim()">
              {{ metadataBusy ? '提交中…' : '解析 Magnet 元数据' }}
            </button>
            <button v-if="metadataJob && ['QUEUED','RUNNING'].includes(metadataJob.state)"
                    type="button" class="rounded-lg border border-slate-200 px-3 py-2 text-sm" :disabled="metadataBusy" @click="stopMetadata">取消解析</button>
          </div>
        </form>
        <p v-if="!metadataEnabled" class="mt-3 text-sm text-amber-700">服务器未启用 Native Metadata Lookup，请使用 qBittorrent 或上传 .torrent。</p>
        <div v-if="metadataJob" class="mt-4 space-y-2 rounded-lg bg-slate-50 p-3 text-sm">
          <p>解析状态：<strong>{{ metadataStatusText[metadataJob.state] || metadataJob.state }}</strong> · {{ metadataJob.name || metadataJob.infoHash || '' }}</p>
          <p v-if="metadataJob.state === 'SUCCEEDED'" class="text-emerald-700">已发现 {{ metadataJob.fileCount }} 个文件，合计 {{ nbytes(metadataJob.totalBytes) }}</p>
          <p v-if="metadataJob.error" class="text-red-600">{{ metadataJob.error }}</p>
          <div v-if="metadataFiles.length" class="max-h-40 space-y-1 overflow-y-auto text-xs text-slate-600">
            <label v-for="file in metadataFiles" :key="file.index" class="flex items-center gap-2 break-all">
              <input type="checkbox" :checked="metadataSelected.has(file.index)" @change="toggleMetadataFile(file.index)">
              <span>{{ file.path }} · {{ nbytes(file.bytes) }}</span>
            </label>
            <div class="flex flex-wrap items-center justify-between gap-2 border-t border-slate-200 pt-2">
              <span>已选 {{ metadataSelected.size }} / {{ metadataTotalFiles }} 个文件 · 第 {{ metadataPage }} 页</span>
              <div class="flex items-center gap-2">
                <button type="button" class="rounded border border-slate-200 px-2 py-1 disabled:opacity-40"
                        :disabled="metadataPageBusy || metadataPage <= 1" @click="loadMetadataPage(metadataPage - 1)">上一页</button>
                <button type="button" class="rounded border border-slate-200 px-2 py-1 disabled:opacity-40"
                        :disabled="metadataPageBusy || metadataPage * metadataPageSize >= metadataTotalFiles"
                        @click="loadMetadataPage(metadataPage + 1)">下一页</button>
              </div>
            </div>
          </div>
          <div v-if="metadataJob.state === 'SUCCEEDED'" class="space-y-2">
            <button v-if="canCreate" class="btn-primary text-sm" :disabled="!metadataSelected.size || metadataBusy" @click="createFromMetadata">
              {{ metadataBusy ? '创建中…' : '使用所选文件创建 Native 任务' }}
            </button>
            <p class="text-xs text-amber-700">新解析的公开 Magnet 若成功取得完整 BEP9 元数据，可直接创建任务；已持久化的完整元数据也可在服务重启后恢复。仅有历史摘要的旧记录仍需上传 .torrent。创建不会自动开始下载。</p>
          </div>
        </div>
      </section>

      <section v-if="canSelectRoot" class="mb-6 rounded-card border border-slate-100 bg-white p-5 shadow-card">
        <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 class="text-lg font-semibold text-slate-800">下载保存目录</h2>
          <div class="flex flex-wrap gap-2">
            <button class="rounded-lg border border-slate-200 px-3 py-2 text-sm hover:bg-slate-50"
                    :disabled="rootBusy" @click="openRootBrowser('')">选择目录</button>
            <button v-if="downloadRoot?.rootPath"
                    class="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50"
                    :disabled="rootBusy" @click="restoreDefaultRoot">恢复默认</button>
          </div>
        </div>
        <p class="text-xs text-slate-500">新任务会保存到该目录下按用户与任务隔离的子目录中，只能浏览服务器批准的目录范围。</p>
        <p class="mt-2 break-all text-sm text-slate-700">
          当前目录：<span class="font-medium">{{ downloadRoot?.rootPath || '服务器默认目录' }}</span>
        </p>
        <p v-if="!downloadRoot?.rootPath && downloadRoot?.defaultRootPath"
           class="mt-1 break-all font-mono text-xs text-slate-400">{{ downloadRoot.defaultRootPath }}</p>
      </section>

      <section v-if="canCreate" class="mb-6 rounded-card border border-slate-100 bg-white p-5 shadow-card">
        <div class="mb-4 flex items-center justify-between gap-4">
          <h2 class="text-lg font-semibold text-slate-800">创建公开种子任务</h2>
          <label class="cursor-pointer rounded-lg border border-slate-200 px-4 py-2 text-sm hover:bg-slate-50">
            选择 .torrent
            <input class="sr-only" type="file" accept=".torrent,application/x-bittorrent" :disabled="loading" @change="inspectFile">
          </label>
        </div>
        <p class="text-xs text-slate-500">先离线验证 InfoHash 和文件列表，再勾选文件创建任务；不会自动启动网络下载。</p>
        <div v-if="inspected" class="mt-4">
          <div class="mb-2 text-sm font-medium text-slate-700">{{ inspected.name }} · {{ nbytes(inspected.totalBytes) }}</div>
          <p class="mb-3 break-all font-mono text-xs text-slate-400">{{ inspected.infoHash }}</p>
          <div class="mb-2 flex items-center justify-between text-xs text-slate-500">
            <span>文件选择：{{ selected.size }} / {{ inspected.files.length }}</span>
            <button class="text-primary hover:underline" @click="toggleAll">全选 / 取消</button>
          </div>
          <div class="max-h-56 space-y-1 overflow-y-auto rounded-lg border border-slate-100 p-2">
            <label v-for="file in inspected.files" :key="file.index" class="flex cursor-pointer items-center gap-3 rounded px-2 py-1.5 text-sm hover:bg-slate-50">
              <input type="checkbox" :checked="selected.has(file.index)" @change="selection(file.index)">
              <span class="min-w-0 flex-1 break-all text-slate-700">{{ file.path }}</span>
              <span class="shrink-0 text-xs text-slate-400">{{ nbytes(file.size) }}</span>
            </label>
          </div>
          <button class="btn-primary mt-4" :disabled="loading || !selected.size" @click="create">
            {{ loading ? '处理中…' : '仅创建任务' }}
          </button>
        </div>
      </section>

      <section v-if="canManage" class="rounded-card border border-slate-100 bg-white p-5 shadow-card">
        <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 class="text-lg font-semibold text-slate-800">我的 Native 任务</h2>
          <button class="text-sm text-primary hover:underline" :disabled="polling" @click="refresh">刷新列表</button>
        </div>
        <p v-if="error" role="alert" class="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-600">{{ error }}</p>
        <p v-if="message" role="status" class="mb-3 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">{{ message }}</p>
        <div v-if="canRun && ownerQueue" class="mb-3 flex flex-wrap gap-4 rounded-lg border border-slate-100 bg-slate-50 p-3 text-xs text-slate-600" role="status">
          <span>我的排队作业：{{ ownerQueue.queuedJobs }}</span>
          <span>运行中：{{ ownerQueue.runningJobs }}</span>
          <span>执行中：{{ ownerQueue.executingJobs }}</span>
        </div>
        <div v-if="canRun" class="mb-4 flex flex-wrap items-center gap-3 rounded-lg bg-slate-50 px-3 py-3 text-sm">
          <label class="flex items-center gap-1.5">
            <span class="text-slate-600">每轮 Piece</span>
            <select v-model.number="piecesPerRound" class="rounded border border-slate-200 bg-white px-2 py-1">
              <option v-for="n in [4, 8, 16, 32, 64, 128, 256]" :key="n" :value="n">{{ n }}</option>
            </select>
          </label>
          <span v-if="health?.parallelPieceEnabled" class="text-xs text-slate-600">
            并发上限：{{ health.parallelPieceWorkers }} 路（按 Peer 与内存动态调整）
          </span>
          <label v-if="canContinuous" class="flex items-center gap-1.5">
            <input v-model="continuous" type="checkbox">
            连续下载模式
          </label>
          <template v-if="continuous && canContinuous">
            <label class="flex items-center gap-1.5">
              <span class="text-slate-600">最多轮次</span>
              <input v-model.number="maxRounds" class="w-20 rounded border border-slate-200 px-2 py-1" type="number" min="1" max="10000">
            </label>
            <label class="flex items-center gap-1.5">
              <span class="text-slate-600">总预算（秒）</span>
              <input v-model.number="maxSeconds" class="w-24 rounded border border-slate-200 px-2 py-1" type="number" min="10" max="86400">
            </label>
            <label class="flex items-center gap-1.5 font-medium text-primary">
              <input v-model="autoContinuous" type="checkbox">
              突破围墙·自动全速续跑（直至完成）
            </label>
          </template>
          <span class="text-xs text-slate-400">
            （提示：上方参数在点击“启动”或“重启”时生效；若任务正在运行，可直接点击下方【以每轮 {{ piecesPerRound }} 块重启】）
          </span>
        </div>
        <div v-if="tasks.length === 0" class="py-10 text-center text-sm text-slate-400">
          暂无任务
        </div>
        <div v-for="task in tasks" :key="task.taskUuid" class="border-t border-slate-100 py-4">
          <div class="mb-2 flex flex-wrap items-center justify-between gap-2">
            <span class="font-mono text-xs text-slate-500" :title="task.taskUuid">{{ shortId(task.taskUuid) }}</span>
            <span class="rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-600">{{ statusText[task.state] || task.state }}</span>
          </div>
          <div class="mb-2 h-2 overflow-hidden rounded-full bg-slate-100">
            <div class="h-full rounded-full bg-primary transition-all duration-300" :style="{ width: displayPercent(task) + '%' }"></div>
          </div>
          <div class="flex flex-wrap justify-between gap-2 text-xs text-slate-500">
            <span>{{ displayPercent(task) }}% · {{ nbytes(displayVerified(task)) }} / {{ nbytes(task.selectedBytes) }}</span>
            <span>版本 {{ task.revision }}</span>
          </div>
          <div v-if="jobs[task.taskUuid]" class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
            <span>作业：{{ statusText[jobs[task.taskUuid].state] || jobs[task.taskUuid].state }}</span>
            <span v-if="metrics[task.taskUuid]">第 {{ metrics[task.taskUuid].rounds }} 轮</span>
            <span v-if="metrics[task.taskUuid] && metrics[task.taskUuid].committedPieces > 0">已下 {{ metrics[task.taskUuid].committedPieces }} 块</span>
            <span v-if="metrics[task.taskUuid]?.peerPoolCount > 0" class="text-slate-500">动态 Peer 池 {{ metrics[task.taskUuid].peerPoolCount }} · 已证实可传输 {{ metrics[task.taskUuid].verifiedPeerCount }} · 故障 {{ metrics[task.taskUuid].degradedPeerCount }} · 刷新 {{ metrics[task.taskUuid].peerRefreshes }} 次</span>
            <span v-else-if="jobs[task.taskUuid].candidatePeers > 0">候选 {{ jobs[task.taskUuid].candidatePeers }} 个 Peer</span>
            <span v-if="metrics[task.taskUuid] && jobs[task.taskUuid]?.state === 'RUNNING'"
              class="font-medium text-emerald-600">实时网络接收 {{ nbytes(metrics[task.taskUuid].networkBytesPerSecond) }}/s</span>
            <span v-if="metrics[task.taskUuid]" class="text-slate-500">已验证速度 {{ displaySpeed(task) }}/s</span>
            <span v-if="metrics[task.taskUuid] && metrics[task.taskUuid].averageBytesPerSecond > 0" class="text-slate-400">已验证均速 {{ nbytes(metrics[task.taskUuid].averageBytesPerSecond) }}/s</span>
            <span v-if="metrics[task.taskUuid] && metrics[task.taskUuid].receivedPayloadBytes > 0"
              class="text-slate-400">本次作业网络累计接收 {{ nbytes(metrics[task.taskUuid].receivedPayloadBytes) }}（含重试，不等于文件进度）</span>
            <span v-if="metrics[task.taskUuid]?.stagedVerifiedBytes > 0"
              class="font-medium text-amber-700">本轮已校验待提交 {{ nbytes(metrics[task.taskUuid].stagedVerifiedBytes) }}（{{ metrics[task.taskUuid].stagedPieces }} Piece）</span>
            <span v-if="metrics[task.taskUuid]?.stagedVerifiedBytes > 0"
              class="font-medium text-blue-700">已校验总量（含待提交）{{ nbytes(Math.min(task.selectedBytes, displayVerified(task) + metrics[task.taskUuid].stagedVerifiedBytes)) }}</span>
            <span v-if="metrics[task.taskUuid]?.peerRequestQueue && jobs[task.taskUuid]?.state === 'RUNNING'"
              class="text-slate-500">Peer 请求队列 {{ metrics[task.taskUuid].peerRequestQueue.outstandingBlocks }}/{{ metrics[task.taskUuid].peerRequestQueue.capacityBlocks }}（占用 {{ metrics[task.taskUuid].peerRequestQueue.occupancyPercent }}%）· Block RTT P50 {{ metrics[task.taskUuid].peerRequestQueue.rttP50Millis }}ms / P95 {{ metrics[task.taskUuid].peerRequestQueue.rttP95Millis }}ms · 请求 {{ metrics[task.taskUuid].peerRequestQueue.sentBlocks }} / 已收 {{ metrics[task.taskUuid].peerRequestQueue.acknowledgedBlocks }}</span>
            <span v-if="metrics[task.taskUuid] && jobs[task.taskUuid]?.state === 'RUNNING'"
              class="text-slate-500">Peer TCP：新建 {{ metrics[task.taskUuid].openedPeerSockets }} · 复用 {{ metrics[task.taskUuid].reusedPeerSockets }}（跨轮 {{ metrics[task.taskUuid].crossRoundPeerReuses }}） · 空闲缓存 {{ metrics[task.taskUuid].idlePeerSockets }} · 退役 {{ metrics[task.taskUuid].retiredPeerSockets || 0 }} · Actor {{ metrics[task.taskUuid].activePeerActors || 0 }}/{{ metrics[task.taskUuid].targetPeerActors || 0 }} · 换租 {{ metrics[task.taskUuid].actorPeerReassignments || 0 }}</span>
          </div>
          <p v-if="jobs[task.taskUuid]?.state === 'RUNNING' && (!metrics[task.taskUuid] || metrics[task.taskUuid].rounds === 0)"
            class="mt-2 text-xs text-amber-700">
            首轮正在进行 Peer 发现、握手或 Piece 传输；网络字节与待提交 Piece 会实时刷新，进度条仅展示已完成 Manifest + 数据库提交的可靠进度。
          </p>
          <p v-if="suspectedOrphan(task)" class="mt-2 text-xs text-amber-700">
            数据库显示 RUNNING，但当前进程没有活动作业。仅在确认单实例部署后才能显式恢复。
          </p>
          <p v-else-if="task.state === 'RUNNING' && lookupState[task.taskUuid] === 'error'"
            class="mt-2 text-xs text-amber-700">
            无法确认当前 Worker 状态，暂不允许恢复；请刷新重试。
          </p>
          <div class="mt-3 flex flex-wrap gap-2">
            <button v-if="canRun && ['CREATED', 'READY', 'PAUSED', 'FAILED'].includes(task.state)"
              class="btn-primary text-sm" :disabled="!!busyId || !!activeJob(task)" @click="updateTask(task, 'start')">
              {{ busyId === task.taskUuid ? '处理中…' : '启动极速下载' }}
            </button>
            <button v-if="activeJob(task)"
              class="rounded-lg border border-primary/40 bg-primary/5 px-3 py-2 text-sm text-primary hover:bg-primary/10"
              :disabled="!!busyId" @click="restartTask(task)">
              {{ busyId === task.taskUuid ? '处理中…' : `以每轮 ${piecesPerRound} 块重启` }}
            </button>
            <button v-if="activeJob(task) || task.state === 'READY'"
              class="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-700 hover:bg-slate-50"
              :disabled="!!busyId" @click="updateTask(task, 'pause')">暂停</button>
            <button v-if="suspectedOrphan(task) && orphanRecoveryEnabled"
              class="rounded-lg border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-800 hover:bg-amber-100"
              :disabled="!!busyId" @click="updateTask(task, 'recover')">恢复孤儿任务</button>
            <button v-if="!activeJob(task) && task.state !== 'RUNNING'"
              class="rounded-lg border border-red-200 px-3 py-2 text-sm text-red-600 hover:bg-red-50"
              :disabled="!!busyId" @click="updateTask(task, 'delete')">删除记录</button>
          </div>
        </div>
        <div class="mt-4 flex justify-end gap-3 text-sm">
          <button class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40"
            :disabled="page === 1" @click="turnPage(-1)">上一页</button>
          <span class="self-center text-slate-500">第 {{ page }} 页</span>
          <button class="rounded-lg border border-slate-200 px-3 py-1.5 disabled:opacity-40"
            :disabled="tasks.length < pageSize" @click="turnPage(1)">下一页</button>
        </div>
      </section>
      <p class="mt-5 text-xs text-slate-400">实验功能：任务作业在服务器内存中管理，页面刷新后可重新关联当前进程中的作业；服务器重启后旧作业编号不保留；下载文件保留在服务端受控目录，暂不提供原生文件导出。</p>

      <div v-if="rootModal" class="fixed inset-0 z-40 flex items-center justify-center bg-slate-900/40 p-4"
           @click.self="rootModal = false">
        <div class="w-full max-w-lg rounded-card bg-white p-5 shadow-card">
          <div class="mb-3 flex items-center justify-between">
            <h3 class="text-base font-semibold text-slate-800">选择下载保存目录</h3>
            <button class="text-sm text-slate-400 hover:text-slate-600" @click="rootModal = false">关闭</button>
          </div>
          <p class="mb-3 break-all font-mono text-xs text-slate-500">{{ rootBrowse?.path || '服务器批准的下载根目录' }}</p>
          <div class="max-h-64 space-y-1 overflow-y-auto rounded-lg border border-slate-100 p-2">
            <button v-if="rootBrowse?.path && rootBrowse?.parent"
                    class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
                    :disabled="rootBusy" @click="openRootBrowser(rootBrowse.parent)">返回上级目录</button>
            <button v-for="entry in (rootBrowse?.entries || [])" :key="entry.path"
                    class="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-40"
                    :disabled="rootBusy" @click="openRootBrowser(entry.path)">
              <span class="shrink-0 text-slate-400">文件夹</span>
              <span class="min-w-0 flex-1 break-all">{{ entry.name }}</span>
            </button>
            <p v-if="!rootBrowse?.entries?.length" class="px-2 py-6 text-center text-sm text-slate-400">该目录下没有可选的子文件夹</p>
          </div>
          <div class="mt-4 flex flex-wrap items-center justify-end gap-3">
            <button class="rounded-lg border border-slate-200 px-3 py-2 text-sm" :disabled="rootBusy" @click="rootModal = false">取消</button>
            <button class="btn-primary text-sm" :disabled="rootBusy || !rootBrowse?.path" @click="chooseRoot">
              {{ rootBusy ? '处理中…' : '选择此目录' }}
            </button>
          </div>
        </div>
      </div>
    </template>
  </main>
</template>
