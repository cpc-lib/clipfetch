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

const BASE = '/magnet/v2'
const TICKET_PREFIX = '/api/magnet/v2/downloads/stream/'
const TICKET_TOKEN = /^[A-Za-z0-9_-]{32,64}$/

export const health = () => unwrap(request.get(`${BASE}/engine/health`))

export const submitMagnet = magnetUri => unwrap(request.post(`${BASE}/parses`, { magnetUri }))

export function submitTorrent(file) {
  const form = new FormData()
  form.append('file', file)
  return unwrap(request.post(`${BASE}/parses/torrent`, form))
}

export const parseDetail = parseUuid =>
  unwrap(request.get(`${BASE}/parses/${encodeURIComponent(parseUuid)}`))

export const listParses = (page = 0, size = 20) =>
  unwrap(request.get(`${BASE}/parses`, { params: { page, size } }))

export const createTask = (parseUuid, selectedFileIndexes) =>
  unwrap(request.post(`${BASE}/tasks`, { parseUuid, selectedFileIndexes }))

export const listTasks = (page = 0, size = 20) =>
  unwrap(request.get(`${BASE}/tasks`, { params: { page, size } }))

export const taskDetail = taskUuid =>
  unwrap(request.get(`${BASE}/tasks/${encodeURIComponent(taskUuid)}`))

export const pauseTask = taskUuid =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/pause`))

export const resumeTask = taskUuid =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(taskUuid)}/resume`))

export async function fileTicket(taskUuid, fileIndex) {
  const data = await unwrap(request.post(
    `${BASE}/tasks/${encodeURIComponent(taskUuid)}/file-ticket`, { fileIndex }))
  const url = data?.url
  if (typeof url !== 'string' || !url.startsWith(TICKET_PREFIX)
    || !TICKET_TOKEN.test(url.slice(TICKET_PREFIX.length))) {
    throw new Error('下载凭证无效，请重试')
  }
  return url
}
