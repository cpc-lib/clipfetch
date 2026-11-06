import request, { errMsg } from './request'

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
  const data = error?.response?.data
  if (data instanceof Blob) {
    try {
      const parsed = JSON.parse(await data.text())
      if (parsed?.error) return parsed.error
    } catch {
      // ignore non-json blob error
    }
  }
  return errMsg(error)
}

const BASE = '/ed2k'

export function fetchEd2kEngineHealth() {
  return unwrap(request.get(`${BASE}/engine/health`))
}

export function accelerateEd2kEngine() {
  return unwrap(request.post(`${BASE}/engine/accelerate`))
}

export function createEd2kTask(link) {
  return unwrap(request.post(`${BASE}/tasks`, { link }))
}

export function fetchEd2kTasks({ page = 1, size = 10 } = {}) {
  return unwrap(request.get(`${BASE}/tasks`, { params: { page, size } }))
}

export function fetchEd2kTaskDetail(taskUuid) {
  return unwrap(request.get(`${BASE}/tasks/${encodeURIComponent(taskUuid)}`))
}

export function pauseEd2kTask(taskUuid, expectedRevision) {
  return unwrap(request.post(
    `${BASE}/tasks/${encodeURIComponent(taskUuid)}/pause`,
    expectedRevision != null ? { expectedRevision } : {}
  ))
}

export function resumeEd2kTask(taskUuid, expectedRevision) {
  return unwrap(request.post(
    `${BASE}/tasks/${encodeURIComponent(taskUuid)}/resume`,
    expectedRevision != null ? { expectedRevision } : {}
  ))
}

export function accelerateEd2kTask(taskUuid) {
  return unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/accelerate`))
}

export function deleteEd2kTask(taskUuid) {
  return unwrap(request.delete(`${BASE}/tasks/${encodeURIComponent(taskUuid)}`))
}

/**
 * 携带 JWT 鉴权拉取已完成文件 Blob 并触发浏览器本地保存（不走免登录白名单，不在 URL 拼 token）
 */
export async function downloadEd2kFileToBrowser(taskUuid, fileIndex = 0, fileName = 'download.bin') {
  try {
    const resp = await request.get(
      `${BASE}/tasks/${encodeURIComponent(taskUuid)}/files/${fileIndex}/content`,
      { responseType: 'blob', timeout: 0 }
    )
    const blobUrl = URL.createObjectURL(resp.data)
    try {
      const a = document.createElement('a')
      a.href = blobUrl
      a.download = fileName || 'download.bin'
      a.rel = 'noreferrer'
      document.body.appendChild(a)
      a.click()
      a.remove()
    } finally {
      setTimeout(() => URL.revokeObjectURL(blobUrl), 1000)
    }
  } catch (error) {
    throw new Error(await blobErrMsg(error))
  }
}
