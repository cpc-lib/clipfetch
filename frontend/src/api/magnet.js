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
export function parseMagnet(magnetUri, force = false) {
  return wrap(request.post('/magnet/parse', { magnetUri, force }))
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

/** Bearer token 只用于签发文件级下载凭证，浏览器原生流式下载，不创建 Blob。 */
export async function downloadFileToBrowser(hash, fileIndex) {
  const data = await wrap(request.post(`/magnet/downloads/${hash}/file-ticket`, { fileIndex }))
  const prefix = '/api/magnet/downloads/stream/'
  if (!data?.url?.startsWith(prefix) ||
      !/^[A-Za-z0-9_-]{43}$/.test(data.url.slice(prefix.length))) {
    throw new Error('下载凭证格式错误')
  }
  const link = document.createElement('a')
  link.href = data.url
  link.rel = 'noreferrer'
  document.body.appendChild(link)
  link.click()
  link.remove()
}
