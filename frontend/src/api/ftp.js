import request, { errMsg } from './request'

const BASE = '/ftp'

function unwrap(promise) {
  return promise.then(({ data }) => {
    if (!data?.success) throw new Error(data?.error || '操作失败')
    return data.data
  }).catch(error => {
    if (error.response) throw new Error(errMsg(error))
    throw error
  })
}

async function blobErrMsg(error) {
  if (error?.response?.data instanceof Blob) {
    try {
      const parsed = JSON.parse(await error.response.data.text())
      if (parsed?.error) return parsed.error
    } catch {
      // ignore non-json blob error
    }
  }
  return errMsg(error)
}

export function fetchFtpHealth() {
  return unwrap(request.get(`${BASE}/engine/health`))
}

export function testFtpConnection(payload) {
  return unwrap(request.post(`${BASE}/connections/test`, payload))
}

export function listFtpDirectory(payload) {
  return unwrap(request.post(`${BASE}/directories/list`, payload))
}

export function createFtpDirectory(payload) {
  return unwrap(request.post(`${BASE}/directories/mkdir`, payload))
}

export function deleteFtpEntry(payload) {
  return unwrap(request.post(`${BASE}/entries/delete`, payload))
}

export function deleteFtpEntriesBatch(payload) {
  return unwrap(request.post(`${BASE}/entries/delete-batch`, payload))
}

export function moveFtpEntriesBatch(payload) {
  return unwrap(request.post(`${BASE}/entries/move-batch`, payload))
}

export function uploadFtpFile(formData, onUploadProgress) {
  return unwrap(request.post(`${BASE}/files/upload`, formData, {
    timeout: 0,
    onUploadProgress
  }))
}

export function fetchFtpTasks({ page = 1, size = 10, signal } = {}) {
  return unwrap(request.get(`${BASE}/tasks`, { params: { page, size }, signal }))
}

export function pauseFtpTask(taskUuid, expectedRevision) {
  return unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/pause`, { expectedRevision }))
}

export function resumeFtpTask(taskUuid, expectedRevision) {
  return unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/resume`, { expectedRevision }))
}

export function restartFtpTask(taskUuid, expectedRevision) {
  return unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/restart`, { expectedRevision }))
}

export function deleteFtpTask(taskUuid, expectedRevision) {
  return unwrap(request.delete(`${BASE}/tasks/${encodeURIComponent(taskUuid)}`, { params: { expectedRevision } }))
}

export async function downloadFtpFile(taskUuid, fileName) {
  try {
    const response = await request.get(
      `${BASE}/tasks/${encodeURIComponent(taskUuid)}/files/0/content`,
      { responseType: 'blob', timeout: 0 }
    )
    const url = URL.createObjectURL(response.data)
    try {
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = fileName || 'download.bin'
      anchor.rel = 'noreferrer'
      document.body.appendChild(anchor)
      anchor.click()
      anchor.remove()
    } finally {
      setTimeout(() => URL.revokeObjectURL(url), 60000)
    }
  } catch (error) {
    throw new Error(await blobErrMsg(error))
  }
}

export async function fetchFtpFileBlob(payload, fallbackFileName) {
  try {
    const response = await request.post(
      `${BASE}/files/stream-download`,
      payload,
      { responseType: 'blob', timeout: 0 }
    )
    let filename = fallbackFileName || 'download.bin'
    const disposition = response.headers && (response.headers['content-disposition'] || response.headers['Content-Disposition'])
    if (disposition) {
      const matchUtf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i)
      if (matchUtf8 && matchUtf8[1]) {
        try {
          filename = decodeURIComponent(matchUtf8[1])
        } catch (_) {}
      } else {
        const matchPlain = disposition.match(/filename="?([^";]+)"?/i)
        if (matchPlain && matchPlain[1]) {
          filename = matchPlain[1]
        }
      }
    }
    return { blob: response.data, filename }
  } catch (error) {
    throw new Error(await blobErrMsg(error))
  }
}

export async function streamDownloadFtpFile(payload, fallbackFileName, dirHandle = null) {
  const { blob, filename } = await fetchFtpFileBlob(payload, fallbackFileName)
  if (dirHandle && typeof dirHandle.getFileHandle === 'function') {
    // 直接写入用户指定的本地保存文件夹，绝不触发浏览器另存为弹窗！
    const fileHandle = await dirHandle.getFileHandle(filename, { create: true })
    const writable = await fileHandle.createWritable()
    try {
      await writable.write(blob)
    } finally {
      await writable.close()
    }
    return filename
  }

  // 未指定文件夹或不支持 DirectoryPicker 时降级为原生触发下载
  const url = URL.createObjectURL(blob)
  try {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.rel = 'noreferrer'
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
  } finally {
    setTimeout(() => URL.revokeObjectURL(url), 60000)
  }
  return filename
}
