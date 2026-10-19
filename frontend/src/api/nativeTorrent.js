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

const BASE = '/magnet/native'

export const nativeHealth = () => unwrap(request.get(`${BASE}/engine/health`))
export const startNativeMetadata = (magnetUri, consentToPublicDiscovery) =>
  unwrap(request.post(`${BASE}/metadata/jobs`, { magnetUri, consentToPublicDiscovery }))
export const nativeMetadataStatus = jobId =>
  unwrap(request.get(`${BASE}/metadata/jobs/${encodeURIComponent(jobId)}`))
export const nativeMetadataFiles = (jobId, page = 1, pageSize = 30) =>
  unwrap(request.get(`${BASE}/metadata/jobs/${encodeURIComponent(jobId)}/files`,
    { params: { page, pageSize } }))
export const cancelNativeMetadata = jobId =>
  unwrap(request.delete(`${BASE}/metadata/jobs/${encodeURIComponent(jobId)}`))
export const createNativeFromMetadata = (jobId, fileIndexes) =>
  unwrap(request.post(`${BASE}/tasks/from-metadata/${encodeURIComponent(jobId)}`,
    { fileIndexes }))
export function inspectNativeTorrent(file) {
  const form = new FormData()
  form.append('file', file)
  return unwrap(request.post(`${BASE}/torrent/inspect`, form))
}
export function createNativeTask(file, indexes) {
  const form = new FormData()
  form.append('file', file)
  indexes.forEach(index => form.append('fileIndexes', String(index)))
  return unwrap(request.post(`${BASE}/tasks`, form))
}
export const nativeDownloadRoot = () => unwrap(request.get(`${BASE}/download-roots`))
export const browseNativeDownloadRoots = (path = '') =>
  unwrap(request.get(`${BASE}/download-roots/browse`, { params: path ? { path } : {} }))
export const saveNativeDownloadRoot = path =>
  unwrap(request.put(`${BASE}/download-roots`, { path }))
export const clearNativeDownloadRoot = () => unwrap(request.delete(`${BASE}/download-roots`))
export const listNativeTasks = (page = 1, pageSize = 20) =>
  unwrap(request.get(`${BASE}/tasks`, { params: { page, pageSize } }))
export const getNativeTask = uuid => unwrap(request.get(`${BASE}/tasks/${encodeURIComponent(uuid)}`))
export const prepareNativeTask = (uuid, expectedRevision) =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(uuid)}/prepare`, { expectedRevision }))
export const pauseNativeTask = (uuid, expectedRevision) =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(uuid)}/pause`, { expectedRevision }))
export const deleteNativeTask = (uuid, expectedRevision) =>
  unwrap(request.delete(`${BASE}/tasks/${encodeURIComponent(uuid)}`, { params: { expectedRevision } }))
export const runNativeRound = (uuid, expectedRevision, maxPieces) =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(uuid)}/rounds`, { expectedRevision, maxPieces }))
export const runNativeContinuous = (uuid, expectedRevision, piecesPerRound, maxRounds, maxSeconds) =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(uuid)}/continuous`,
    { expectedRevision, piecesPerRound, maxRounds, maxSeconds }))
export const latestNativeTaskJob = uuid =>
  unwrap(request.get(`${BASE}/tasks/${encodeURIComponent(uuid)}/worker-job`))
export const recoverOrphanNativeTask = (uuid, expectedRevision) =>
  unwrap(request.post(`${BASE}/tasks/${encodeURIComponent(uuid)}/recover-orphan`, { expectedRevision }))
export const nativeOwnerQueueHealth = () =>
  unwrap(request.get(`${BASE}/worker-jobs/queue-health`))
export const nativeJobStatus = jobId =>
  unwrap(request.get(`${BASE}/worker-jobs/${encodeURIComponent(jobId)}`))
export const nativeJobMetrics = jobId =>
  unwrap(request.get(`${BASE}/worker-jobs/${encodeURIComponent(jobId)}/metrics`))
export const cancelNativeJob = jobId =>
  unwrap(request.delete(`${BASE}/worker-jobs/${encodeURIComponent(jobId)}`))
