<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { isLoggedIn } from '../stores/auth'
import {
  createFtpDirectory,
  deleteFtpEntriesBatch,
  deleteFtpEntry,
  deleteFtpTask,
  downloadFtpFile,
  fetchFtpHealth,
  fetchFtpTasks,
  listFtpDirectory,
  moveFtpEntriesBatch,
  pauseFtpTask,
  restartFtpTask,
  resumeFtpTask,
  streamDownloadFtpFile,
  testFtpConnection,
  uploadFtpFile
} from '../api/ftp'

const health = ref({
  configured: false,
  connected: false,
  version: '',
  message: isLoggedIn.value ? '正在检测 FTP 模块...' : '登录后可检查模块状态并管理下载任务'
})
const healthChecking = ref(false)
const listLoading = ref(false)
const submitting = ref(false)
const testing = ref(false)
const showPassword = ref(false)
const errorBanner = ref('')
const successBanner = ref('')
const tasks = ref([])
const page = ref(1)
const size = ref(10)
const total = ref(0)
const busyIds = ref(new Set())
const downloadingIds = ref(new Set())
const confirmDeleteUuid = ref(null)
const confirmRestartUuid = ref(null)

const browsePath = ref('/')
const currentDirectoryPath = ref('/')
const parentDirectoryPath = ref(null)
const directoryEntries = ref([])
const browsingDirectory = ref(false)
const directoryLoaded = ref(false)

const newFolderName = ref('')
const creatingDirectory = ref(false)
const confirmDeleteEntry = ref(null)
const deletingEntryPath = ref('')

const uploadFileInput = ref(null)
const selectedUploadFiles = ref([])
const customUploadFileName = ref('')
const uploadingFile = ref(false)
const uploadProgress = ref(0)

const selectedUploadTotalBytes = computed(() =>
  selectedUploadFiles.value.reduce((sum, file) => sum + (Number(file?.size) || 0), 0)
)

const selectedRemotePaths = ref([])
const downloadingAllCompleted = ref(false)
const downloadingLocalPaths = ref(new Set())
const batchDownloadingLocal = ref(false)
const downloadingFromForm = ref(false)

const supportsDirectoryPicker = typeof window !== 'undefined' && 'showDirectoryPicker' in window
const localSaveDirHandle = ref(null)
const localSaveDirName = ref('')

async function chooseLocalSaveDirectory() {
  if (!supportsDirectoryPicker) {
    errorBanner.value = '当前浏览器不支持直接选择文件夹，下载将按浏览器默认设置保存'
    return null
  }
  try {
    const handle = await window.showDirectoryPicker({ mode: 'readwrite' })
    localSaveDirHandle.value = handle
    localSaveDirName.value = handle.name || '本地目录'
    successBanner.value = `已选定本地保存目录：📁 ${localSaveDirName.value}（下载将直接存入该目录，无需每次弹窗）`
    return handle
  } catch (err) {
    if (err.name === 'AbortError') {
      return null
    }
    errorBanner.value = '选择本地文件夹失败：' + (err.message || err)
    return null
  }
}

function clearLocalSaveDirectory() {
  localSaveDirHandle.value = null
  localSaveDirName.value = ''
  successBanner.value = '已清除本地保存目录设置'
}

function isDownloadingLocal(path) {
  return downloadingLocalPaths.value.has(path)
}

const confirmBatchDelete = ref(false)
const batchDeleting = ref(false)
const confirmBatchMove = ref(false)
const targetMoveDirectory = ref('')
const batchMoving = ref(false)
const currentSubDirectories = computed(() =>
  directoryEntries.value.filter(entry => entry.type === 'DIRECTORY')
)

const onlyFiles = computed(() => directoryEntries.value.filter(entry => entry.type === 'FILE'))
const selectableEntries = computed(() =>
  directoryEntries.value.filter(entry => entry.type === 'FILE' || entry.type === 'DIRECTORY')
)
const isAllEntriesSelected = computed(() =>
  selectableEntries.value.length > 0 && selectedRemotePaths.value.length === selectableEntries.value.length
)
const selectedFiles = computed(() =>
  directoryEntries.value.filter(entry => entry.type === 'FILE' && selectedRemotePaths.value.includes(entry.path))
)
const selectedEntries = computed(() =>
  directoryEntries.value.filter(entry => selectedRemotePaths.value.includes(entry.path))
)
const completedTasks = computed(() => tasks.value.filter(task => task.downloadable))

const form = reactive({
  protocol: 'FTPS_EXPLICIT',
  host: '',
  port: 21,
  remotePath: '',
  authMode: 'CREDENTIALS',
  username: '',
  password: '',
  displayName: '',
  expectedSha256: ''
})

let pollTimer = null
let pollInFlight = false
let pollAbortController = null

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const hasActiveTasks = computed(() => tasks.value.some(task =>
  ['QUEUED', 'CONNECTING', 'DOWNLOADING', 'PAUSING', 'RETRY_WAIT', 'VERIFYING'].includes(task.status)
))

function isBusy(taskUuid) {
  return busyIds.value.has(taskUuid)
}

function setBusy(taskUuid, active) {
  const next = new Set(busyIds.value)
  if (active) next.add(taskUuid)
  else next.delete(taskUuid)
  busyIds.value = next
}

function isDownloading(taskUuid) {
  return downloadingIds.value.has(taskUuid)
}

function setDownloading(taskUuid, active) {
  const next = new Set(downloadingIds.value)
  if (active) next.add(taskUuid)
  else next.delete(taskUuid)
  downloadingIds.value = next
}

function requestPayload() {
  return {
    protocol: form.protocol,
    host: form.host.trim(),
    port: Number(form.port || 21),
    remotePath: form.remotePath.trim(),
    authMode: form.authMode,
    username: form.authMode === 'CREDENTIALS' ? form.username : null,
    password: form.authMode === 'CREDENTIALS' ? form.password : null,
    displayName: form.displayName.trim() || null,
    expectedSha256: form.expectedSha256.trim() || null
  }
}

function validateConnectionBase() {
  if (!form.host.trim()) return '请先填写 FTP 服务器主机名或 IP'
  if (!Number.isInteger(Number(form.port)) || Number(form.port) < 1 || Number(form.port) > 65535) return '端口必须在 1 到 65535 之间'
  if (form.authMode === 'CREDENTIALS' && (!form.username || !form.password)) return '请先填写 FTP 用户名和密码'
  return ''
}

function connectionBasePayload() {
  return {
    protocol: form.protocol,
    host: form.host.trim(),
    port: Number(form.port || 21),
    authMode: form.authMode,
    username: form.authMode === 'CREDENTIALS' ? form.username : null,
    password: form.authMode === 'CREDENTIALS' ? form.password : null
  }
}

function validateForm() {
  const baseErr = validateConnectionBase()
  if (baseErr) return baseErr
  if (!form.remotePath.trim()) return '请填写服务器主机名和远端文件路径'
  if (form.expectedSha256 && !/^[0-9a-fA-F]{64}$/.test(form.expectedSha256.trim())) return 'SHA-256 必须是 64 位十六进制字符串'
  return ''
}

function stopPolling() {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (pollAbortController) {
    pollAbortController.abort()
    pollAbortController = null
  }
}

function syncPolling() {
  if (!isLoggedIn.value || !health.value.connected || !hasActiveTasks.value) {
    stopPolling()
    return
  }
  if (pollTimer !== null) return
  pollTimer = setInterval(async () => {
    if (pollInFlight || !health.value.connected) return
    pollInFlight = true
    pollAbortController = new AbortController()
    try {
      const response = await fetchFtpTasks({
        page: page.value,
        size: size.value,
        signal: pollAbortController.signal
      })
      tasks.value = response.items || []
      total.value = response.total || 0
      if (!hasActiveTasks.value) stopPolling()
    } catch (error) {
      if (error?.code === 'ERR_CANCELED') return
      stopPolling()
      errorBanner.value = error.message || 'FTP 任务轮询失败，已停止自动刷新'
    } finally {
      pollAbortController = null
      pollInFlight = false
    }
  }, 1000)
}

async function loadTasks() {
  if (!isLoggedIn.value) return
  listLoading.value = true
  try {
    const response = await fetchFtpTasks({ page: page.value, size: size.value })
    tasks.value = response.items || []
    total.value = response.total || 0
    syncPolling()
  } catch (error) {
    stopPolling()
    errorBanner.value = error.message || '加载 FTP 下载任务失败'
  } finally {
    listLoading.value = false
  }
}

async function refreshWorkspace() {
  if (!isLoggedIn.value) return
  healthChecking.value = true
  errorBanner.value = ''
  try {
    health.value = await fetchFtpHealth()
  } catch (error) {
    health.value = { configured: false, connected: false, version: '', message: error.message || 'FTP 模块状态检查失败' }
    stopPolling()
  } finally {
    healthChecking.value = false
  }
  await loadTasks()
}

async function handleTest() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  if (form.expectedSha256 && !/^[0-9a-fA-F]{64}$/.test(form.expectedSha256.trim())) {
    errorBanner.value = 'SHA-256 必须是 64 位十六进制字符串'
    return
  }
  testing.value = true
  errorBanner.value = ''
  successBanner.value = ''
  try {
    const result = await testFtpConnection(requestPayload())
    successBanner.value = result.remoteSize == null
      ? result.message
      : `${result.message}，远端大小 ${formatBytes(result.remoteSize)}`
  } catch (error) {
    errorBanner.value = error.message || 'FTP 连接测试失败'
  } finally {
    testing.value = false
  }
}

async function handleBrowseDirectory(targetPath) {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  const pathToLoad = typeof targetPath === 'string' && targetPath.trim()
    ? targetPath.trim()
    : (browsePath.value.trim() || '/')
  browsingDirectory.value = true
  errorBanner.value = ''
  try {
    const result = await listFtpDirectory({
      ...connectionBasePayload(),
      remotePath: pathToLoad
    })
    currentDirectoryPath.value = result.currentPath || '/'
    browsePath.value = currentDirectoryPath.value
    parentDirectoryPath.value = result.parentPath || null
    directoryEntries.value = result.entries || []
    selectedRemotePaths.value = []
    directoryLoaded.value = true
  } catch (error) {
    errorBanner.value = error.message || '读取 FTP 远端目录失败'
  } finally {
    browsingDirectory.value = false
  }
}

async function handleCreateDirectory() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  const folder = newFolderName.value.trim()
  if (!folder) {
    errorBanner.value = '请输入新建文件夹名称'
    return
  }
  creatingDirectory.value = true
  errorBanner.value = ''
  successBanner.value = ''
  try {
    const parent = currentDirectoryPath.value || browsePath.value.trim() || '/'
    const res = await createFtpDirectory({
      ...connectionBasePayload(),
      parentPath: parent,
      directoryName: folder
    })
    newFolderName.value = ''
    successBanner.value = res?.message || `已创建文件夹 ${res?.createdPath || folder}`
    await handleBrowseDirectory(parent)
  } catch (error) {
    errorBanner.value = error.message || '创建 FTP 远端文件夹失败'
  } finally {
    creatingDirectory.value = false
  }
}

async function handleDeleteEntry(entry) {
  if (!entry) return
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  deletingEntryPath.value = entry.path
  errorBanner.value = ''
  successBanner.value = ''
  try {
    const res = await deleteFtpEntry({
      ...connectionBasePayload(),
      remotePath: entry.path,
      entryType: entry.type
    })
    confirmDeleteEntry.value = null
    successBanner.value = res?.message || `已删除远端条目 ${entry.path}`
    await handleBrowseDirectory(currentDirectoryPath.value || '/')
  } catch (error) {
    errorBanner.value = error.message || '删除 FTP 远端条目失败'
  } finally {
    deletingEntryPath.value = ''
  }
}

function handleSelectUploadFile(event) {
  const files = Array.from(event?.target?.files || [])
  selectedUploadFiles.value = files
  if (files.length === 1) {
    customUploadFileName.value = files[0].name
  } else {
    customUploadFileName.value = ''
  }
}

function removeSelectedUploadFile(index) {
  if (uploadingFile.value) return
  const next = selectedUploadFiles.value.filter((_, idx) => idx !== index)
  selectedUploadFiles.value = next
  if (next.length === 1) {
    customUploadFileName.value = next[0].name
  } else if (next.length === 0) {
    customUploadFileName.value = ''
    if (uploadFileInput.value) {
      uploadFileInput.value.value = ''
    }
  }
}

function clearSelectedUploadFiles() {
  if (uploadingFile.value) return
  selectedUploadFiles.value = []
  customUploadFileName.value = ''
  if (uploadFileInput.value) {
    uploadFileInput.value.value = ''
  }
}

async function handleUploadFile() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  if (!selectedUploadFiles.value.length) {
    errorBanner.value = '请先选择要上传的本地文件'
    return
  }
  const targetDir = currentDirectoryPath.value || browsePath.value.trim() || '/'
  const filesToUpload = selectedUploadFiles.value
  const formData = new FormData()
  if (filesToUpload.length === 1) {
    formData.append('file', filesToUpload[0])
    if (customUploadFileName.value.trim()) {
      formData.append('remoteFileName', customUploadFileName.value.trim())
    }
  } else {
    for (const file of filesToUpload) {
      formData.append('files', file)
    }
  }
  formData.append('protocol', form.protocol)
  formData.append('host', form.host.trim())
  formData.append('port', String(Number(form.port || 21)))
  formData.append('remoteDirectory', targetDir)
  formData.append('authMode', form.authMode)
  if (form.authMode === 'CREDENTIALS') {
    formData.append('username', form.username)
    formData.append('password', form.password)
  }

  uploadingFile.value = true
  uploadProgress.value = 0
  errorBanner.value = ''
  successBanner.value = ''
  try {
    const res = await uploadFtpFile(formData, progressEvent => {
      if (progressEvent?.total > 0) {
        uploadProgress.value = Math.min(100, Math.round((progressEvent.loaded * 100) / progressEvent.total))
      }
    })
    successBanner.value = res?.message || `已上传 ${filesToUpload.length} 个文件（${formatBytes(res?.uploadedBytes)}）`
    clearSelectedUploadFiles()
    await handleBrowseDirectory(targetDir)
  } catch (error) {
    errorBanner.value = error.message || '上传文件到 FTP 服务器失败'
  } finally {
    uploadingFile.value = false
    uploadProgress.value = 0
  }
}

function toggleSelectFile(entry) {
  const path = entry.path
  const next = new Set(selectedRemotePaths.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  selectedRemotePaths.value = Array.from(next)
}

function toggleSelectAllEntries() {
  if (isAllEntriesSelected.value) {
    selectedRemotePaths.value = []
  } else {
    selectedRemotePaths.value = selectableEntries.value.map(file => file.path)
  }
}

function clearSelectedRemoteFiles() {
  selectedRemotePaths.value = []
}

async function handleBatchDeleteConfirmed() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    confirmBatchDelete.value = false
    return
  }
  const entriesToDelete = selectedEntries.value
  if (!entriesToDelete.length) {
    errorBanner.value = '未找到待删除的条目'
    confirmBatchDelete.value = false
    return
  }
  batchDeleting.value = true
  errorBanner.value = ''
  try {
    const items = entriesToDelete.map(e => ({
      remotePath: e.path,
      entryType: e.type === 'DIRECTORY' ? 'DIRECTORY' : 'FILE'
    }))
    const res = await deleteFtpEntriesBatch({
      ...connectionBasePayload(),
      items
    })
    successBanner.value = res?.message || `已成功批量删除 ${items.length} 个远端条目`
    clearSelectedRemoteFiles()
    confirmBatchDelete.value = false
    await handleBrowseDirectory(currentDirectoryPath.value)
  } catch (error) {
    errorBanner.value = error.message || '批量删除失败'
  } finally {
    batchDeleting.value = false
  }
}

function openBatchMoveModal() {
  const current = currentDirectoryPath.value || '/'
  targetMoveDirectory.value = current
  confirmBatchMove.value = true
}

async function handleBatchMoveConfirmed() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    confirmBatchMove.value = false
    return
  }
  if (!selectedRemotePaths.value.length) {
    errorBanner.value = '请先勾选待移动的条目'
    confirmBatchMove.value = false
    return
  }
  const targetDir = targetMoveDirectory.value.trim()
  if (!targetDir) {
    errorBanner.value = '请输入目标新目录路径'
    return
  }
  batchMoving.value = true
  errorBanner.value = ''
  try {
    const res = await moveFtpEntriesBatch({
      ...connectionBasePayload(),
      targetDirectory: targetDir,
      sourcePaths: selectedRemotePaths.value
    })
    successBanner.value = res?.message || `已成功移动 ${selectedRemotePaths.value.length} 个条目至 ${targetDir}`
    clearSelectedRemoteFiles()
    confirmBatchMove.value = false
    await handleBrowseDirectory(currentDirectoryPath.value)
  } catch (error) {
    errorBanner.value = error.message || '批量移动失败'
  } finally {
    batchMoving.value = false
  }
}

async function handleDirectDownloadToLocal(entry) {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  const path = entry.path
  const next = new Set(downloadingLocalPaths.value)
  next.add(path)
  downloadingLocalPaths.value = next
  errorBanner.value = ''
  successBanner.value = `正在从 FTP 直接下载并保存至本地：${entry.name}...`
  try {
    const filename = await streamDownloadFtpFile({
      ...connectionBasePayload(),
      remotePath: entry.path
    }, entry.name, localSaveDirHandle.value)
    if (localSaveDirHandle.value) {
      successBanner.value = `已成功将「${filename || entry.name}」直接保存至已选目录（${localSaveDirName.value}）`
    } else {
      successBanner.value = `已成功将「${filename || entry.name}」直接下载保存至本地目录`
    }
  } catch (error) {
    errorBanner.value = error.message || `下载「${entry.name}」到本地失败`
  } finally {
    const cleaned = new Set(downloadingLocalPaths.value)
    cleaned.delete(path)
    downloadingLocalPaths.value = cleaned
  }
}

async function handleBatchDownloadToLocal() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  if (!selectedRemotePaths.value.length) {
    errorBanner.value = '请先勾选要下载的远端文件'
    return
  }
  const selectedEntries = directoryEntries.value.filter(
    entry => entry.type === 'FILE' && selectedRemotePaths.value.includes(entry.path)
  )
  if (!selectedEntries.length) {
    errorBanner.value = '未找到可下载的文件'
    return
  }

  // 若尚未选择本地保存目录且浏览器支持选择文件夹，先弹一次目录选择器，一次选定后全量静默写入，避免每个文件都弹窗
  let targetDirHandle = localSaveDirHandle.value
  if (!targetDirHandle && supportsDirectoryPicker) {
    targetDirHandle = await chooseLocalSaveDirectory()
    if (!targetDirHandle) {
      errorBanner.value = '已取消选择保存目录，批量下载未开始'
      return
    }
  }

  batchDownloadingLocal.value = true
  errorBanner.value = ''
  let successCount = 0
  const totalCount = selectedEntries.length
  try {
    for (let i = 0; i < totalCount; i++) {
      const entry = selectedEntries[i]
      successBanner.value = `正在下载第 ${i + 1}/${totalCount} 个文件到本地：${entry.name}...`
      const next = new Set(downloadingLocalPaths.value)
      next.add(entry.path)
      downloadingLocalPaths.value = next
      try {
        await streamDownloadFtpFile({
          ...connectionBasePayload(),
          remotePath: entry.path
        }, entry.name, targetDirHandle)
        successCount++
      } finally {
        const cleaned = new Set(downloadingLocalPaths.value)
        cleaned.delete(entry.path)
        downloadingLocalPaths.value = cleaned
      }
      if (i < totalCount - 1) {
        await new Promise(r => setTimeout(r, 200))
      }
    }
    const saveLocationText = targetDirHandle ? `至已选目录（${localSaveDirName.value}）` : '至本地目录'
    successBanner.value = `已成功将全部 ${successCount} 个文件直接下载保存${saveLocationText}`
    clearSelectedRemoteFiles()
  } catch (error) {
    errorBanner.value = error.message || `批量下载到本地中断（已完成 ${successCount}/${totalCount}）`
  } finally {
    batchDownloadingLocal.value = false
  }
}

async function handleDownloadAllToLocal() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  if (!onlyFiles.value.length) {
    errorBanner.value = '当前目录下没有可下载的文件'
    return
  }

  // 若尚未选择本地保存目录且浏览器支持选择文件夹，先弹一次目录选择器，一次选定后全量静默写入，避免每个文件都弹窗
  let targetDirHandle = localSaveDirHandle.value
  if (!targetDirHandle && supportsDirectoryPicker) {
    targetDirHandle = await chooseLocalSaveDirectory()
    if (!targetDirHandle) {
      errorBanner.value = '已取消选择保存目录，下载未开始'
      return
    }
  }

  batchDownloadingLocal.value = true
  errorBanner.value = ''
  let successCount = 0
  const totalCount = onlyFiles.value.length
  try {
    for (let i = 0; i < totalCount; i++) {
      const entry = onlyFiles.value[i]
      successBanner.value = `正在下载第 ${i + 1}/${totalCount} 个文件到本地：${entry.name}...`
      const next = new Set(downloadingLocalPaths.value)
      next.add(entry.path)
      downloadingLocalPaths.value = next
      try {
        await streamDownloadFtpFile({
          ...connectionBasePayload(),
          remotePath: entry.path
        }, entry.name, targetDirHandle)
        successCount++
      } finally {
        const cleaned = new Set(downloadingLocalPaths.value)
        cleaned.delete(entry.path)
        downloadingLocalPaths.value = cleaned
      }
      if (i < totalCount - 1) {
        await new Promise(r => setTimeout(r, 200))
      }
    }
    const saveLocationText = targetDirHandle ? `至已选目录（${localSaveDirName.value}）` : '至本地目录'
    successBanner.value = `已成功将当前目录全部 ${successCount} 个文件直接下载保存${saveLocationText}`
    clearSelectedRemoteFiles()
  } catch (error) {
    errorBanner.value = error.message || `全部下载到本地中断（已完成 ${successCount}/${totalCount}）`
  } finally {
    batchDownloadingLocal.value = false
  }
}

async function handleDownloadAllCompleted() {
  const list = completedTasks.value
  if (!list.length || downloadingAllCompleted.value) return
  downloadingAllCompleted.value = true
  errorBanner.value = ''
  try {
    for (const task of list) {
      await downloadFtpFile(task.taskUuid, task.displayName)
      await new Promise(resolve => setTimeout(resolve, 400))
    }
  } catch (error) {
    errorBanner.value = error.message || '取回文件遇到问题'
  } finally {
    downloadingAllCompleted.value = false
  }
}

async function handleDirectDownloadFromForm() {
  const baseErr = validateConnectionBase()
  if (baseErr) {
    errorBanner.value = baseErr
    return
  }
  const remotePath = form.remotePath.trim()
  if (!remotePath) {
    errorBanner.value = '请填写待下载的远端文件路径'
    return
  }
  const suggestedName = form.displayName.trim() || remotePath.split('/').filter(Boolean).pop() || 'download.bin'

  let targetDirHandle = localSaveDirHandle.value
  if (!targetDirHandle && supportsDirectoryPicker) {
    targetDirHandle = await chooseLocalSaveDirectory()
    if (!targetDirHandle) {
      errorBanner.value = '已取消选择本地下载保存路径，下载未开始'
      return
    }
  }

  downloadingFromForm.value = true
  errorBanner.value = ''
  successBanner.value = `正在从 FTP 直接下载并保存至本地：${suggestedName}...`
  try {
    const filename = await streamDownloadFtpFile({
      ...connectionBasePayload(),
      remotePath
    }, suggestedName, targetDirHandle)

    if (localSaveDirName.value) {
      successBanner.value = `已成功将「${filename || suggestedName}」直接下载保存至本地路径（${localSaveDirName.value}）`
    } else {
      successBanner.value = `已成功将「${filename || suggestedName}」直接下载保存至本地`
    }
  } catch (error) {
    errorBanner.value = error.message || `下载「${suggestedName}」到本地失败`
  } finally {
    downloadingFromForm.value = false
  }
}

async function handleCreate() {
  await handleDirectDownloadFromForm()
}

async function runAction(task, action, fallback) {
  setBusy(task.taskUuid, true)
  errorBanner.value = ''
  try {
    const updated = await action(task.taskUuid, task.revision)
    const index = tasks.value.findIndex(item => item.taskUuid === updated.taskUuid)
    if (index >= 0) tasks.value.splice(index, 1, updated)
    syncPolling()
  } catch (error) {
    errorBanner.value = error.message || fallback
    await loadTasks()
  } finally {
    setBusy(task.taskUuid, false)
  }
}

async function handleDelete(task) {
  setBusy(task.taskUuid, true)
  try {
    await deleteFtpTask(task.taskUuid, task.revision)
    confirmDeleteUuid.value = null
    await loadTasks()
  } catch (error) {
    errorBanner.value = error.message || '删除 FTP 任务失败'
  } finally {
    setBusy(task.taskUuid, false)
  }
}

async function handleRestart(task) {
  confirmRestartUuid.value = null
  await runAction(task, restartFtpTask, '从头重下失败')
}

async function handleDownload(task) {
  setDownloading(task.taskUuid, true)
  errorBanner.value = ''
  try {
    await downloadFtpFile(task.taskUuid, task.displayName)
  } catch (error) {
    errorBanner.value = error.message || '取回 FTP 文件失败'
  } finally {
    setDownloading(task.taskUuid, false)
  }
}

function changePage(next) {
  if (next < 1 || next > totalPages.value) return
  page.value = next
  loadTasks()
}

function formatBytes(value) {
  if (value === null || value === undefined || value === '') return '未知'
  const bytes = Number(value)
  if (!Number.isFinite(bytes) || bytes < 0) return '未知'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / Math.pow(1024, index)).toFixed(index === 0 ? 0 : 2)} ${units[index]}`
}

function stateLabel(status) {
  return ({
    QUEUED: '排队中', CONNECTING: '连接中', DOWNLOADING: '下载中', PAUSING: '暂停中', DELETING: '删除中',
    PAUSED: '已暂停', RETRY_WAIT: '等待重试', VERIFYING: '校验中', COMPLETED: '已完成',
    FAILED: '失败', NEEDS_RESTART: '需从头重下', NEEDS_VERIFICATION: '待完整性确认',
    BLOCKED_CREDENTIALS: '凭据不可用'
  })[status] || status
}

function badgeClass(status) {
  if (status === 'COMPLETED') return 'border-emerald-200 bg-emerald-50 text-emerald-700'
  if (status === 'DOWNLOADING' || status === 'CONNECTING') return 'border-blue-200 bg-blue-50 text-blue-700'
  if (status === 'FAILED' || status === 'BLOCKED_CREDENTIALS') return 'border-red-200 bg-red-50 text-red-700'
  if (status === 'NEEDS_RESTART' || status === 'NEEDS_VERIFICATION' || status === 'RETRY_WAIT') return 'border-amber-200 bg-amber-50 text-amber-700'
  return 'border-slate-200 bg-slate-100 text-slate-600'
}

watch(isLoggedIn, loggedIn => {
  if (loggedIn) refreshWorkspace()
  else {
    stopPolling()
    health.value = { configured: false, connected: false, version: '', message: '登录后可检查模块状态并管理下载任务' }
    tasks.value = []
    total.value = 0
  }
})

onMounted(() => {
  if (isLoggedIn.value) refreshWorkspace()
})

onUnmounted(stopPolling)
</script>

<template>
  <section class="mx-auto max-w-6xl space-y-6 px-4 py-8 sm:px-6">
    <div class="flex flex-col gap-4 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:flex-row sm:items-center sm:justify-between">
      <div class="flex items-start gap-3">
        <span class="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full" :class="health.connected ? 'bg-emerald-500' : 'bg-slate-400'" />
        <div>
          <div class="flex flex-wrap items-center gap-2">
            <h1 class="text-xl font-semibold tracking-tight text-slate-900">FTP / FTPS 下载</h1>
            <span class="rounded-full border px-2.5 py-1 text-xs font-medium" :class="health.connected ? 'border-emerald-200 bg-emerald-50 text-emerald-700' : 'border-slate-200 bg-slate-100 text-slate-600'">
              {{ health.connected ? `已就绪${health.version ? ' · ' + health.version : ''}` : '未启用' }}
            </span>
          </div>
          <p class="mt-1 text-sm text-slate-500">{{ health.message }}</p>
        </div>
      </div>
      <button type="button" :disabled="healthChecking || listLoading" class="self-end rounded-xl border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700 transition hover:border-slate-300 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500/20 disabled:opacity-50 sm:self-auto" @click="refreshWorkspace">
        {{ healthChecking ? '检测中...' : '刷新状态' }}
      </button>
    </div>

    <div v-if="errorBanner" class="flex items-center justify-between gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
      <span>{{ errorBanner }}</span><button type="button" class="shrink-0 text-xs font-medium hover:text-red-900" @click="errorBanner = ''">关闭</button>
    </div>
    <div v-if="successBanner" class="flex items-center justify-between gap-3 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
      <span>{{ successBanner }}</span><button type="button" class="shrink-0 text-xs font-medium hover:text-emerald-900" @click="successBanner = ''">关闭</button>
    </div>

    <form class="space-y-5 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6" @submit.prevent="handleCreate">
      <div>
        <h2 class="text-base font-semibold text-slate-900">创建单文件下载</h2>
        <p class="mt-1 text-sm text-slate-500">服务器地址需由管理员网络策略准许；凭据不会出现在任务列表或下载 URL 中。</p>
      </div>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <label class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>协议</span>
          <select v-model="form.protocol" class="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20">
            <option value="FTPS_EXPLICIT">显式 FTPS（推荐）</option>
            <option value="FTP">FTP（明文）</option>
          </select>
        </label>
        <label class="space-y-1.5 text-sm font-medium text-slate-700 sm:col-span-1 lg:col-span-2">
          <span>服务器主机名</span>
          <input v-model.trim="form.host" type="text" autocomplete="off" placeholder="ftp.example.com" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
        <label class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>端口</span>
          <input v-model.number="form.port" type="number" min="1" max="65535" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm tabular-nums focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
      </div>
      <div v-if="form.protocol === 'FTP'" class="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
        普通 FTP 会以明文传输账号、密码和文件内容，仅在管理员明确启用且网络可信时使用。
      </div>
      <div class="grid gap-4 sm:grid-cols-2">
        <label class="space-y-1.5 text-sm font-medium text-slate-700 sm:col-span-2">
          <span>远端文件路径</span>
          <input v-model="form.remotePath" type="text" autocomplete="off" placeholder="/pub/releases/archive.zip" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 font-mono text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
        <label class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>显示文件名（可选）</span>
          <input v-model="form.displayName" type="text" placeholder="archive.zip" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
        <label class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>期望 SHA-256（可选）</span>
          <input v-model="form.expectedSha256" type="text" spellcheck="false" placeholder="64 位十六进制摘要" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 font-mono text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
      </div>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <label class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>认证方式</span>
          <select v-model="form.authMode" class="w-full rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20">
            <option value="CREDENTIALS">账号密码</option>
            <option value="ANONYMOUS">匿名访问</option>
          </select>
        </label>
        <label v-if="form.authMode === 'CREDENTIALS'" class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>用户名</span>
          <input v-model="form.username" type="text" autocomplete="username" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
        </label>
        <label v-if="form.authMode === 'CREDENTIALS'" class="space-y-1.5 text-sm font-medium text-slate-700">
          <span>密码</span>
          <span class="relative block">
            <input v-model="form.password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" class="w-full rounded-xl border border-slate-200 px-3 py-2.5 pr-12 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20" />
            <button type="button" class="absolute inset-y-0 right-0 px-3 text-xs font-medium text-slate-500 transition hover:text-blue-600" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">{{ showPassword ? '隐藏' : '显示' }}</button>
          </span>
        </label>
      </div>

      <!-- 下载保存路径（本地目录）配置 -->
      <div class="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3.5">
        <div class="flex flex-wrap items-center justify-between gap-2.5">
          <div class="flex flex-wrap items-center gap-2">
            <span class="text-xs font-semibold text-slate-700">下载保存路径：</span>
            <span
              v-if="localSaveDirName"
              class="inline-flex items-center gap-1.5 rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1 text-xs font-mono font-medium text-blue-800"
            >
              📁 {{ localSaveDirName }}
              <button
                type="button"
                class="ml-1 rounded px-1 text-blue-600 transition hover:bg-blue-100 hover:text-blue-800"
                title="更换下载保存路径"
                @click="chooseLocalSaveDirectory"
              >
                更换
              </button>
              <button
                type="button"
                class="rounded px-1 text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                title="清除已选保存路径"
                @click="clearLocalSaveDirectory"
              >
                ×
              </button>
            </span>
            <button
              v-else-if="supportsDirectoryPicker"
              type="button"
              class="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 shadow-xs transition hover:border-blue-400 hover:bg-blue-50/60 hover:text-blue-700"
              title="点击预选本地下载路径，下载时直接静默保存，无需每个文件弹窗确认"
              @click="chooseLocalSaveDirectory"
            >
              📁 选择本地下载保存路径（下载不弹窗）
            </button>
            <span v-else class="text-xs text-slate-400">
              （当前环境使用浏览器默认下载设置）
            </span>
          </div>
          <span class="text-[11px] text-slate-500">
            直接流式下载保存至本地电脑 · 零服务端落盘
          </span>
        </div>
      </div>

      <div class="flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-end">
        <button type="button" :disabled="browsingDirectory || !health.connected" class="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500/20 disabled:opacity-50" @click="handleBrowseDirectory(browsePath)">{{ browsingDirectory ? '读取目录中...' : '浏览远程目录' }}</button>
        <button type="button" :disabled="testing || downloadingFromForm || !health.connected" class="rounded-xl border border-slate-200 px-4 py-2.5 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500/20 disabled:opacity-50" @click="handleTest">{{ testing ? '测试中...' : '测试连接' }}</button>
        <button
          type="submit"
          :disabled="downloadingFromForm || testing || !health.connected"
          class="rounded-xl bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white shadow-xs transition hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500/30 disabled:opacity-50"
          title="直接从 FTP 流式下载并保存至已选本地下载路径（零服务端落盘）"
        >
          {{ downloadingFromForm ? '正在下载到本地...' : '直接下载到本地' }}
        </button>
      </div>
    </form>

    <!-- 远端文件目录浏览、新建文件夹与上传文件工作区 -->
    <div class="space-y-5 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6">
      <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 class="text-base font-semibold text-slate-900">远程文件目录 / 新建文件夹 / 上传文件</h2>
          <p class="mt-0.5 text-xs text-slate-500">使用上方服务器与认证信息查看远程目录、创建文件夹或上传本地文件到当前目录。</p>
        </div>
        <div class="flex flex-wrap items-center gap-2">
          <button
            v-if="parentDirectoryPath"
            type="button"
            :disabled="browsingDirectory || !health.connected"
            class="rounded-xl border border-slate-200 px-3.5 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            @click="handleBrowseDirectory(parentDirectoryPath)"
          >
            返回上级 ({{ parentDirectoryPath }})
          </button>
          <button
            type="button"
            :disabled="browsingDirectory || !health.connected"
            class="rounded-xl border border-slate-200 px-3.5 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            @click="handleBrowseDirectory(currentDirectoryPath)"
          >
            {{ browsingDirectory ? '刷新中...' : '刷新当前目录' }}
          </button>
        </div>
      </div>

      <!-- 目录路径导航栏 -->
      <div class="flex flex-col gap-2 sm:flex-row">
        <input
          v-model="browsePath"
          type="text"
          placeholder="/ 或 /pub"
          class="flex-1 rounded-xl border border-slate-200 px-3.5 py-2 text-sm font-mono focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
          @keyup.enter="handleBrowseDirectory(browsePath)"
        />
        <button
          type="button"
          :disabled="browsingDirectory || !health.connected"
          class="rounded-xl bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
          @click="handleBrowseDirectory(browsePath)"
        >
          {{ browsingDirectory ? '加载中...' : '进入目录' }}
        </button>
      </div>

      <!-- 新建文件夹 + 上传文件工具栏 -->
      <div class="grid gap-4 rounded-xl border border-slate-100 bg-slate-50/70 p-4 lg:grid-cols-2">
        <!-- 新建文件夹 -->
        <div class="space-y-2">
          <label class="block text-xs font-semibold text-slate-700">
            在当前目录 (<code class="font-mono text-blue-700">{{ currentDirectoryPath }}</code>) 下创建文件夹
          </label>
          <div class="flex gap-2">
            <input
              v-model="newFolderName"
              type="text"
              placeholder="输入新建文件夹名称"
              class="flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
              @keyup.enter="handleCreateDirectory"
            />
            <button
              type="button"
              :disabled="creatingDirectory || !newFolderName.trim() || !health.connected"
              class="shrink-0 rounded-xl border border-blue-200 bg-blue-50 px-4 py-2 text-xs font-semibold text-blue-700 transition hover:bg-blue-100 disabled:opacity-50"
              @click="handleCreateDirectory"
            >
              {{ creatingDirectory ? '创建中...' : '新建文件夹' }}
            </button>
          </div>
        </div>

        <!-- 上传本地文件（支持单选或多选） -->
        <div class="space-y-2">
          <div class="flex items-center justify-between gap-2">
            <label class="block text-xs font-semibold text-slate-700">
              上传本地文件到当前目录 (<code class="font-mono text-blue-700">{{ currentDirectoryPath }}</code>)
            </label>
            <span class="text-[11px] text-slate-400">支持一次选择多个文件</span>
          </div>
          <div class="flex flex-col gap-2 sm:flex-row">
            <input
              ref="uploadFileInput"
              type="file"
              multiple
              class="block w-full text-xs text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-slate-200 file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-slate-800 hover:file:bg-slate-300"
              @change="handleSelectUploadFile"
            />
            <input
              v-model="customUploadFileName"
              type="text"
              :disabled="selectedUploadFiles.length > 1"
              :placeholder="selectedUploadFiles.length > 1 ? '多文件使用原文件名' : '远端保存文件名（可选）'"
              class="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs focus:border-blue-500 focus:outline-none disabled:bg-slate-100 disabled:text-slate-400 sm:w-44"
            />
            <button
              type="button"
              :disabled="uploadingFile || !selectedUploadFiles.length || !health.connected"
              class="shrink-0 rounded-xl bg-emerald-600 px-4 py-2 text-xs font-semibold text-white transition hover:bg-emerald-700 disabled:opacity-50"
              @click="handleUploadFile"
            >
              {{ uploadingFile ? `上传中 ${uploadProgress}%` : (selectedUploadFiles.length > 1 ? `上传 ${selectedUploadFiles.length} 个文件` : '上传文件') }}
            </button>
          </div>
          <div
            v-if="selectedUploadFiles.length"
            class="space-y-1.5 rounded-xl border border-slate-200/80 bg-white p-2.5 text-xs"
          >
            <div class="flex items-center justify-between text-slate-500">
              <span>已选择 <strong class="font-semibold text-slate-700">{{ selectedUploadFiles.length }}</strong> 个文件（共 {{ formatBytes(selectedUploadTotalBytes) }}）</span>
              <button
                type="button"
                :disabled="uploadingFile"
                class="text-xs font-medium text-slate-400 hover:text-red-600 disabled:opacity-50"
                @click="clearSelectedUploadFiles"
              >
                清空已选
              </button>
            </div>
            <div class="flex max-h-28 flex-wrap gap-1.5 overflow-y-auto pt-0.5">
              <span
                v-for="(file, idx) in selectedUploadFiles"
                :key="`${file.name}-${file.size}-${idx}`"
                class="inline-flex max-w-full items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-2 py-1 text-slate-700"
              >
                <span class="truncate max-w-44 font-mono">{{ file.name }}</span>
                <span class="shrink-0 text-[11px] text-slate-400">({{ formatBytes(file.size) }})</span>
                <button
                  type="button"
                  :disabled="uploadingFile"
                  class="shrink-0 text-slate-400 hover:text-red-600 disabled:opacity-50"
                  title="移除此文件"
                  @click="removeSelectedUploadFile(idx)"
                >
                  ×
                </button>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- 目录内容列表 -->
      <div class="overflow-hidden rounded-xl border border-slate-100">
        <div class="flex flex-wrap items-center justify-between gap-2 bg-slate-50 px-4 py-2.5 text-xs text-slate-600">
          <div class="flex items-center gap-2">
            <input
              v-if="selectableEntries.length"
              type="checkbox"
              :checked="isAllEntriesSelected"
              class="h-3.5 w-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
              title="全选/取消全选当前目录所有条目"
              @change="toggleSelectAllEntries"
            />
            <span>当前路径：<strong class="font-mono text-slate-800">{{ currentDirectoryPath }}</strong></span>
          </div>
          <div class="flex items-center gap-2">
            <button
              v-if="onlyFiles.length > 0"
              type="button"
              :disabled="batchDownloadingLocal || !health.connected"
              class="rounded-lg bg-blue-600 px-3 py-1 text-xs font-semibold text-white transition hover:bg-blue-700 disabled:opacity-50"
              title="直接流式将当前目录全部文件依次下载保存到本地电脑（零服务端落盘）"
              @click="handleDownloadAllToLocal"
            >
              {{ batchDownloadingLocal ? '正在下载到本地...' : `全部下载到本地 (${onlyFiles.length})` }}
            </button>
            <span>共 {{ directoryEntries.length }} 项</span>
          </div>
        </div>

        <!-- 批量下载/批量删除选中项条形栏 -->
        <div
          v-if="selectedRemotePaths.length"
          class="flex flex-wrap items-center justify-between gap-2 border-t border-blue-100 bg-blue-50/70 px-4 py-2 text-xs text-blue-800"
        >
          <span>已勾选 <strong class="font-semibold text-blue-900">{{ selectedRemotePaths.length }}</strong> 项</span>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="text-xs font-medium text-slate-500 hover:text-slate-700"
              @click="clearSelectedRemoteFiles"
            >
              取消勾选
            </button>
            <button
              v-if="selectedFiles.length > 0"
              type="button"
              :disabled="batchDownloadingLocal || !health.connected"
              class="rounded-lg bg-blue-600 px-3 py-1 text-xs font-semibold text-white transition hover:bg-blue-700 disabled:opacity-50"
              title="直接流式将勾选的文件依次下载保存到本地电脑（零服务端落盘）"
              @click="handleBatchDownloadToLocal"
            >
              {{ batchDownloadingLocal ? '正在下载到本地...' : `批量下载到本地 (${selectedFiles.length})` }}
            </button>
            <button
              type="button"
              :disabled="batchMoving || batchDeleting || batchDownloadingLocal || !health.connected"
              class="rounded-lg border border-slate-300 bg-white px-3 py-1 text-xs font-semibold text-slate-700 shadow-xs transition hover:border-blue-400 hover:bg-blue-50/60 hover:text-blue-700 disabled:opacity-50"
              title="将选中的文件/文件夹批量移动至远端新目录"
              @click="openBatchMoveModal"
            >
              📂 {{ batchMoving ? '正在移动...' : `批量移动到... (${selectedRemotePaths.length})` }}
            </button>
            <button
              type="button"
              :disabled="batchDeleting || batchMoving || batchDownloadingLocal || !health.connected"
              class="rounded-lg border border-red-200 bg-red-50 px-3 py-1 text-xs font-semibold text-red-600 transition hover:bg-red-100 hover:text-red-700 disabled:opacity-50"
              title="批量删除勾选的远端文件与文件夹"
              @click="confirmBatchDelete = true"
            >
              {{ batchDeleting ? '正在删除...' : `批量删除 (${selectedRemotePaths.length})` }}
            </button>
          </div>
        </div>
        <div class="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 bg-slate-50/80 px-4 py-2 text-xs text-slate-600">
          <div class="flex flex-wrap items-center gap-2">
            <span class="font-medium text-slate-700">本地保存目录：</span>
            <span
              v-if="localSaveDirName"
              class="inline-flex items-center gap-1.5 rounded-lg border border-blue-200 bg-blue-50 px-2.5 py-1 text-xs font-mono font-medium text-blue-800"
            >
              📁 {{ localSaveDirName }}
              <button
                type="button"
                class="ml-1 rounded px-1 text-blue-600 transition hover:bg-blue-100 hover:text-blue-800"
                title="更换保存目录"
                @click="chooseLocalSaveDirectory"
              >
                更换
              </button>
              <button
                type="button"
                class="rounded px-1 text-slate-400 transition hover:bg-red-50 hover:text-red-600"
                title="清除已选目录"
                @click="clearLocalSaveDirectory"
              >
                ×
              </button>
            </span>
            <button
              v-else-if="supportsDirectoryPicker"
              type="button"
              class="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-2.5 py-1 text-xs font-medium text-slate-700 shadow-xs transition hover:border-blue-400 hover:bg-blue-50/50 hover:text-blue-700"
              title="点击预选本地目标文件夹，下载时直接静默保存，无需每个文件弹窗确认"
              @click="chooseLocalSaveDirectory"
            >
              📁 选择保存目录（下载不弹窗）
            </button>
            <span v-else class="text-slate-400">
              （当前环境使用浏览器默认下载设置）
            </span>
          </div>
          <span class="text-slate-400">直接流式下载 · 零服务端落盘</span>
        </div>
        <div v-if="browsingDirectory" class="px-6 py-10 text-center text-sm text-slate-500">
          正在读取远程目录...
        </div>
        <div v-else-if="!directoryLoaded" class="px-6 py-10 text-center text-xs text-slate-400">
          填写上方 FTP 服务器地址与账号密码后，点击「进入目录」或「浏览远程目录」即可查看文件列表
        </div>
        <div v-else-if="!directoryEntries.length" class="px-6 py-10 text-center text-xs text-slate-400">
          当前目录为空
        </div>
        <div v-else class="divide-y divide-slate-100 max-h-80 overflow-y-auto">
          <div
            v-for="entry in directoryEntries"
            :key="entry.path"
            class="flex flex-col gap-2 px-4 py-3 hover:bg-slate-50/70 sm:flex-row sm:items-center sm:justify-between"
          >
            <div class="min-w-0 flex items-center gap-2.5">
              <input
                v-if="entry.type === 'FILE' || entry.type === 'DIRECTORY'"
                type="checkbox"
                :checked="selectedRemotePaths.includes(entry.path)"
                class="h-3.5 w-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                @change="toggleSelectFile(entry)"
              />
              <span
                class="shrink-0 rounded-md px-2 py-0.5 text-xs font-medium border"
                :class="entry.type === 'DIRECTORY'
                  ? 'border-amber-200 bg-amber-50 text-amber-800'
                  : 'border-slate-200 bg-slate-100 text-slate-600'"
              >
                {{ entry.type === 'DIRECTORY' ? '文件夹' : (entry.type === 'SYMLINK' ? '链接' : '文件') }}
              </span>
              <button
                v-if="entry.type === 'DIRECTORY'"
                type="button"
                class="truncate text-left text-sm font-medium text-blue-600 hover:underline"
                @click="handleBrowseDirectory(entry.path)"
              >
                {{ entry.name }}/
              </button>
              <span v-else class="truncate text-sm text-slate-800">{{ entry.name }}</span>
            </div>
            <div class="flex flex-wrap items-center justify-between gap-3 sm:justify-end">
              <span v-if="entry.type !== 'DIRECTORY'" class="text-xs tabular-nums text-slate-500">
                {{ formatBytes(entry.size) }}
              </span>
              <div class="flex items-center gap-1.5">
                <button
                  v-if="entry.type === 'DIRECTORY'"
                  type="button"
                  class="rounded-lg border border-slate-200 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                  @click="handleBrowseDirectory(entry.path)"
                >
                  打开
                </button>
                <template v-else-if="entry.type === 'FILE'">
                  <button
                    type="button"
                    :disabled="isDownloadingLocal(entry.path) || batchDownloadingLocal"
                    class="rounded-lg bg-blue-600 px-2.5 py-1 text-xs font-medium text-white transition hover:bg-blue-700 disabled:opacity-50"
                    title="直接从 FTP 流式下载并保存至本地电脑（零服务端落盘）"
                    @click="handleDirectDownloadToLocal(entry)"
                  >
                    {{ isDownloadingLocal(entry.path) ? '下载中...' : '下载到本地' }}
                  </button>
                </template>
                <button
                  v-if="entry.type === 'FILE' || entry.type === 'DIRECTORY'"
                  type="button"
                  :disabled="Boolean(deletingEntryPath)"
                  class="rounded-lg border border-red-200 px-2.5 py-1 text-xs font-medium text-red-600 transition hover:bg-red-50 disabled:opacity-50"
                  @click="confirmDeleteEntry = entry"
                >
                  {{ deletingEntryPath === entry.path ? '删除中...' : '删除' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 远端文件/文件夹删除确认弹窗 -->
    <div
      v-if="confirmDeleteEntry"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-xs"
      @click.self="!deletingEntryPath && (confirmDeleteEntry = null)"
    >
      <div class="w-full max-w-md space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
        <div class="flex items-start justify-between gap-3">
          <h3 class="text-base font-semibold text-slate-900">
            确认删除远端{{ confirmDeleteEntry.type === 'DIRECTORY' ? '文件夹' : '文件' }}
          </h3>
          <button
            type="button"
            :disabled="Boolean(deletingEntryPath)"
            class="text-xs font-medium text-slate-400 hover:text-slate-600 disabled:opacity-50"
            @click="confirmDeleteEntry = null"
          >
            关闭
          </button>
        </div>
        <div class="space-y-2 text-sm text-slate-600">
          <p>
            确定要直接删除远端{{ confirmDeleteEntry.type === 'DIRECTORY' ? '文件夹' : '文件' }}吗？
          </p>
          <div class="break-all rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-xs text-slate-800">
            {{ confirmDeleteEntry.path }}
          </div>
          <p v-if="confirmDeleteEntry.type === 'DIRECTORY'" class="text-xs text-red-600">
            若该文件夹内包含文件或子文件夹，将一并直接删除且不可恢复（不影响本地已下载文件与任务记录）。
          </p>
          <p v-else class="text-xs text-slate-500">
            此操作仅从 FTP 服务器删除该文件，不可恢复（不影响本地已下载文件与任务记录）。
          </p>
        </div>
        <div class="flex justify-end gap-2 pt-1">
          <button
            type="button"
            :disabled="Boolean(deletingEntryPath)"
            class="rounded-xl border border-slate-200 px-4 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            @click="confirmDeleteEntry = null"
          >
            取消
          </button>
          <button
            type="button"
            :disabled="Boolean(deletingEntryPath)"
            class="rounded-xl bg-red-600 px-4 py-2 text-xs font-semibold text-white transition hover:bg-red-700 disabled:opacity-50"
            @click="handleDeleteEntry(confirmDeleteEntry)"
          >
            {{ deletingEntryPath ? '正在删除...' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 远端条目批量删除确认弹窗 -->
    <div
      v-if="confirmBatchDelete"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-xs"
      @click.self="!batchDeleting && (confirmBatchDelete = false)"
    >
      <div class="w-full max-w-md space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
        <div class="flex items-start justify-between gap-3">
          <h3 class="text-base font-semibold text-slate-900">
            确认批量删除远端条目
          </h3>
          <button
            type="button"
            :disabled="batchDeleting"
            class="text-xs font-medium text-slate-400 hover:text-slate-600 disabled:opacity-50"
            @click="confirmBatchDelete = false"
          >
            关闭
          </button>
        </div>
        <div class="space-y-2 text-sm text-slate-600">
          <p>
            确定要直接删除已选中的 <strong class="font-semibold text-red-600">{{ selectedRemotePaths.length }}</strong> 个远端条目吗？
          </p>
          <div class="max-h-44 overflow-y-auto space-y-1 rounded-xl border border-slate-200 bg-slate-50 p-2.5 font-mono text-xs text-slate-800">
            <div v-for="path in selectedRemotePaths" :key="path" class="truncate">
              {{ path }}
            </div>
          </div>
          <p class="text-xs text-red-600">
            ⚠️ 删除仅作用于远端 FTP 服务器，操作不可撤销；若选中的条目包含文件夹，其内部子目录与文件将被递归清空。
          </p>
        </div>
        <div class="flex justify-end gap-2 pt-1">
          <button
            type="button"
            :disabled="batchDeleting"
            class="rounded-xl border border-slate-200 px-4 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            @click="confirmBatchDelete = false"
          >
            取消
          </button>
          <button
            type="button"
            :disabled="batchDeleting"
            class="rounded-xl bg-red-600 px-4 py-2 text-xs font-semibold text-white transition hover:bg-red-700 disabled:opacity-50"
            @click="handleBatchDeleteConfirmed"
          >
            {{ batchDeleting ? '正在删除...' : '确认批量删除' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 远端条目批量移动到新目录弹窗 -->
    <div
      v-if="confirmBatchMove"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-xs"
      @click.self="!batchMoving && (confirmBatchMove = false)"
    >
      <div class="w-full max-w-lg space-y-4 rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
        <div class="flex items-start justify-between gap-3">
          <div>
            <h3 class="text-base font-semibold text-slate-900">
              批量移动远端条目到新目录
            </h3>
            <p class="mt-0.5 text-xs text-slate-500">
              将已勾选的 {{ selectedRemotePaths.length }} 项移动至指定的远端目录；若目标目录不存在将自动逐级创建。
            </p>
          </div>
          <button
            type="button"
            :disabled="batchMoving"
            class="text-xs font-medium text-slate-400 hover:text-slate-600 disabled:opacity-50"
            @click="confirmBatchMove = false"
          >
            关闭
          </button>
        </div>

        <div class="space-y-3 text-sm text-slate-600">
          <div>
            <label class="block text-xs font-semibold text-slate-700 mb-1.5">
              目标远端目录路径
            </label>
            <div class="flex gap-2">
              <input
                v-model.trim="targetMoveDirectory"
                type="text"
                placeholder="例如：/ 或 /pub/releases/new_folder"
                class="flex-1 rounded-xl border border-slate-200 px-3.5 py-2 font-mono text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500/20"
                @keyup.enter="handleBatchMoveConfirmed"
              />
            </div>
            <!-- 快捷点击填充目标路径 -->
            <div class="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
              <span class="text-slate-400">快捷填入：</span>
              <button
                type="button"
                class="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-slate-700 hover:bg-slate-100"
                @click="targetMoveDirectory = '/'"
              >
                / (根目录)
              </button>
              <button
                v-if="parentDirectoryPath"
                type="button"
                class="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-slate-700 hover:bg-slate-100"
                @click="targetMoveDirectory = parentDirectoryPath"
              >
                {{ parentDirectoryPath }} (上级目录)
              </button>
              <template v-for="dir in currentSubDirectories" :key="dir.path">
                <button
                  type="button"
                  class="rounded-md border border-slate-200 bg-slate-50 px-2 py-0.5 font-mono text-slate-700 hover:bg-slate-100"
                  @click="targetMoveDirectory = dir.path"
                >
                  📁 {{ dir.name }}/
                </button>
              </template>
            </div>
          </div>

          <div>
            <span class="block text-xs font-semibold text-slate-700 mb-1">
              待移动条目清单（{{ selectedRemotePaths.length }} 项）：
            </span>
            <div class="max-h-40 overflow-y-auto space-y-1 rounded-xl border border-slate-200 bg-slate-50 p-2.5 font-mono text-xs text-slate-800">
              <div v-for="path in selectedRemotePaths" :key="path" class="truncate">
                • {{ path }}
              </div>
            </div>
          </div>

          <div class="rounded-xl border border-blue-100 bg-blue-50/70 p-3 text-xs text-blue-800">
            💡 提示：移动操作将直接在远端 FTP 服务器重命名路径，若目标目录不存在将自动为您创建。
          </div>
        </div>

        <div class="flex justify-end gap-2 pt-2">
          <button
            type="button"
            :disabled="batchMoving"
            class="rounded-xl border border-slate-200 px-4 py-2 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
            @click="confirmBatchMove = false"
          >
            取消
          </button>
          <button
            type="button"
            :disabled="batchMoving || !targetMoveDirectory.trim()"
            class="rounded-xl bg-blue-600 px-4 py-2 text-xs font-semibold text-white transition hover:bg-blue-700 disabled:opacity-50"
            @click="handleBatchMoveConfirmed"
          >
            {{ batchMoving ? '正在移动...' : '确认移动' }}
          </button>
        </div>
      </div>
    </div>

    <div class="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm">
      <div class="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-4 sm:px-6">
        <div><h2 class="text-base font-semibold text-slate-900">下载任务</h2><p class="mt-0.5 text-xs text-slate-500">共 {{ total }} 条，仅展示当前账号的数据</p></div>
        <div class="flex items-center gap-2">
          <button
            v-if="completedTasks.length > 1"
            type="button"
            :disabled="downloadingAllCompleted"
            class="rounded-xl border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-semibold text-blue-700 transition hover:bg-blue-100 disabled:opacity-50"
            @click="handleDownloadAllCompleted"
          >
            {{ downloadingAllCompleted ? '正在批量取回中...' : `一键取回全部文件 (${completedTasks.length})` }}
          </button>
          <span v-if="hasActiveTasks && health.connected" class="text-xs font-medium text-blue-600">每秒自动刷新</span>
        </div>
      </div>
      <div v-if="listLoading && !tasks.length" class="px-6 py-14 text-center text-sm text-slate-500">正在加载 FTP 任务...</div>
      <div v-else-if="!tasks.length" class="px-6 py-14 text-center"><p class="text-sm font-medium text-slate-700">还没有 FTP 下载任务</p><p class="mt-1 text-xs text-slate-500">填写上方服务器信息后创建第一条任务。</p></div>
      <div v-else class="divide-y divide-slate-100">
        <article v-for="task in tasks" :key="task.taskUuid" class="space-y-4 px-5 py-5 sm:px-6">
          <div class="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <h3 class="truncate text-sm font-semibold text-slate-900">{{ task.displayName }}</h3>
                <span class="rounded-full border px-2 py-0.5 text-xs font-medium" :class="badgeClass(task.status)">{{ stateLabel(task.status) }}</span>
                <span class="text-xs font-medium" :class="task.protocol === 'FTPS_EXPLICIT' ? 'text-emerald-600' : 'text-amber-600'">{{ task.protocol === 'FTPS_EXPLICIT' ? 'FTPS 加密' : 'FTP 明文' }}</span>
              </div>
              <p class="mt-1 truncate text-xs text-slate-500">{{ task.host }}:{{ task.port }}</p>
            </div>
            <div class="shrink-0 text-left sm:text-right"><div class="text-sm font-semibold tabular-nums text-slate-800">{{ task.progressPercent.toFixed(2) }}%</div><div class="text-xs tabular-nums text-slate-500">{{ formatBytes(task.downloadedBytes) }} / {{ formatBytes(task.totalBytes) }}</div></div>
          </div>
          <div class="h-2 overflow-hidden rounded-full bg-slate-100"><div class="h-full rounded-full bg-blue-600 transition-all duration-300" :style="{ width: `${Math.min(100, task.progressPercent || 0)}%` }" /></div>
          <p v-if="task.errorMessage" class="rounded-lg bg-red-50 px-3 py-2 text-xs text-red-700">{{ task.errorMessage }}</p>
          <div class="flex flex-wrap items-center justify-end gap-2">
            <button v-if="['CONNECTING', 'DOWNLOADING', 'QUEUED', 'RETRY_WAIT'].includes(task.status)" type="button" :disabled="isBusy(task.taskUuid)" class="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-medium text-slate-700 transition hover:bg-slate-50 disabled:opacity-50" @click="runAction(task, pauseFtpTask, '暂停任务失败')">暂停</button>
            <button v-if="['PAUSED', 'FAILED'].includes(task.status)" type="button" :disabled="isBusy(task.taskUuid)" class="rounded-lg border border-blue-200 bg-blue-50 px-3 py-1.5 text-xs font-medium text-blue-700 transition hover:bg-blue-100 disabled:opacity-50" @click="runAction(task, resumeFtpTask, '继续任务失败')">继续</button>
            <button v-if="['NEEDS_RESTART', 'FAILED', 'PAUSED'].includes(task.status)" type="button" :disabled="isBusy(task.taskUuid)" class="rounded-lg border border-amber-200 bg-amber-50 px-3 py-1.5 text-xs font-medium text-amber-700 transition hover:bg-amber-100 disabled:opacity-50" @click="confirmRestartUuid = task.taskUuid">从头重下</button>
            <button v-if="task.downloadable" type="button" :disabled="isDownloading(task.taskUuid)" class="rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-semibold text-white transition hover:bg-blue-700 disabled:opacity-50" @click="handleDownload(task)">{{ isDownloading(task.taskUuid) ? '取回中...' : '取回文件' }}</button>
            <button type="button" :disabled="isBusy(task.taskUuid)" class="rounded-lg border border-red-200 px-3 py-1.5 text-xs font-medium text-red-600 transition hover:bg-red-50 disabled:opacity-50" @click="confirmDeleteUuid = task.taskUuid">删除</button>
          </div>
          <div v-if="confirmRestartUuid === task.taskUuid" class="flex flex-col gap-3 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900 sm:flex-row sm:items-center sm:justify-between"><span>将清空当前检查点和临时文件，并从 0 字节重新下载。</span><div class="flex gap-2"><button type="button" class="rounded-lg px-3 py-1.5 text-xs font-medium hover:bg-amber-100" @click="confirmRestartUuid = null">取消</button><button type="button" class="rounded-lg bg-amber-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-amber-700" @click="handleRestart(task)">确认重下</button></div></div>
          <div v-if="confirmDeleteUuid === task.taskUuid" class="flex flex-col gap-3 rounded-xl border border-red-200 bg-red-50 p-3 text-sm text-red-900 sm:flex-row sm:items-center sm:justify-between"><span>删除会停止任务并清理该任务的服务器端文件，此操作不可恢复。</span><div class="flex gap-2"><button type="button" class="rounded-lg px-3 py-1.5 text-xs font-medium hover:bg-red-100" @click="confirmDeleteUuid = null">取消</button><button type="button" class="rounded-lg bg-red-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-red-700" @click="handleDelete(task)">确认删除</button></div></div>
        </article>
      </div>
      <div v-if="totalPages > 1" class="flex items-center justify-between border-t border-slate-100 bg-slate-50/60 px-5 py-3 text-xs text-slate-600 sm:px-6"><span>第 {{ page }} / {{ totalPages }} 页</span><div class="flex gap-2"><button type="button" :disabled="page <= 1" class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 transition hover:bg-slate-50 disabled:opacity-40" @click="changePage(page - 1)">上一页</button><button type="button" :disabled="page >= totalPages" class="rounded-lg border border-slate-200 bg-white px-3 py-1.5 transition hover:bg-slate-50 disabled:opacity-40" @click="changePage(page + 1)">下一页</button></div></div>
    </div>
  </section>
</template>
