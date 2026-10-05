import request from './request'

/** 第三方字幕服务连接状态：{ connected } */
export function getSubtitleSession() {
  return request.get('/subtitles/session').then((r) => r.data.data)
}

/** 用自己的第三方账号（用户名/密码/租户编码）连接 */
export function connectSubtitle(payload) {
  return request.post('/subtitles/session/connect', payload).then((r) => r.data.data)
}

/** 断开第三方连接 */
export function disconnectSubtitle() {
  return request.delete('/subtitles/session').then((r) => r.data.data)
}

/** 字幕历史记录（全量列表） */
export function listSubtitles() {
  return request.get('/subtitles').then((r) => r.data.data)
}

/** 翻译目标语言列表（租户维护） */
export function listLangs() {
  return request.get('/subtitles/langs').then((r) => r.data.data)
}

/** 字幕详情（含全部字幕条） */
export function getSubtitle(id) {
  return request.get(`/subtitles/${id}`).then((r) => r.data.data)
}

/** 上传字幕文件（.srt / .vtt / .ass） */
export function uploadSubtitle(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/subtitles', form).then((r) => r.data.data)
}

/** 翻译字幕（indices 为空 = 全部翻译） */
export function translateSubtitle(id, targetLang, indices = null) {
  return request.post(`/subtitles/${id}/translate`, { targetLang, indices }).then((r) => r.data.data)
}

/** 保存人工编辑（cues 全量覆盖） */
export function saveSubtitle(id, cues) {
  return request.put(`/subtitles/${id}`, { cues }).then((r) => r.data.data)
}

/** 删除字幕记录 */
export function deleteSubtitle(id) {
  return request.delete(`/subtitles/${id}`).then((r) => r.data.data)
}

/** 下载 SRT（二进制 blob，按响应头文件名保存） */
export async function downloadSubtitle(id) {
  const resp = await request.get(`/subtitles/${id}/download`, { responseType: 'blob' })
  const disposition = resp.headers['content-disposition'] || ''
  let filename = `subtitle-${id}.srt`
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  const plain = /filename="?([^";]+)"?/.exec(disposition)
  if (star) {
    filename = decodeURIComponent(star[1].trim())
  } else if (plain) {
    filename = decodeURIComponent(plain[1].trim())
  }
  const url = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
