import request from './request'
import { errMsg } from './request'

/** 统一包装：提取后端返回的具体错误信息，失败时抛出带业务错误信息的 Error */
function wrap(promise) {
  return promise.then(({ data }) => {
    if (!data.success) throw new Error(data.error || '操作失败')
    return data.data
  }).catch(e => {
    if (e.response) throw new Error(errMsg(e))
    throw e
  })
}

/** 提交磁力解析（返回解析记录，status=PARSING 时需轮询 parseMagnetDetail） */
export function parseMagnet(magnetUri) {
  return wrap(request.post('/magnet/parse', { magnetUri }))
}

/** 查询解析状态及文件列表 */
export function parseMagnetDetail(id) {
  return wrap(request.get(`/magnet/parse/${id}`))
}

/** 分页查询解析历史 */
export function fetchMagnetHistory({ page = 1, size = 10, keyword } = {}) {
  const params = { page, size }
  if (keyword) params.keyword = keyword
  return wrap(request.get('/magnet/history', { params }))
}

/** 删除单条解析历史（逻辑删除，不影响实际下载） */
export function deleteMagnetHistory(id) {
  return wrap(request.delete(`/magnet/history/${id}`))
}

/** 创建下载：按勾选的文件索引设置优先级并开始任务 */
export function createMagnetDownload({ parseId, selectedFileIndexes }) {
  return wrap(request.post('/magnet/downloads', { parseId, selectedFileIndexes }))
}

/** 当前下载任务及实时进度 */
export function fetchMagnetDownloads() {
  return wrap(request.get('/magnet/downloads'))
}

/** 开始/继续下载任务 */
export function startMagnetDownload(hash) {
  return wrap(request.post(`/magnet/downloads/${hash}/start`))
}

/** 暂停下载任务 */
export function pauseMagnetDownload(hash) {
  return wrap(request.post(`/magnet/downloads/${hash}/pause`))
}

/** 删除下载任务（deleteFiles=true 时同时删除已下载文件） */
export function removeMagnetDownload(hash, deleteFiles) {
  return wrap(request.delete(`/magnet/downloads/${hash}`, { params: { deleteFiles } }))
}

/** qBittorrent 引擎健康检查（返回 {version, savePath}） */
export function magnetEngineHealth() {
  return wrap(request.get('/magnet/engine/health'))
}

/** 列出指定下载任务已完成的文件 */
export function fetchCompletedFiles(hash) {
  return wrap(request.get(`/magnet/downloads/${hash}/files`))
}

/** 浏览器下载文件（axios 携带 token 获取 Blob，再触发浏览器保存） */
export async function downloadFileToBrowser(hash, filePath) {
  const resp = await request.get(`/magnet/downloads/${hash}/file`, {
    params: { filePath },
    responseType: 'blob',
    timeout: 300000
  })
  const blob = new Blob([resp.data])
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filePath.split('/').pop() || 'download'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}
