import request, { errMsg } from './request'

/**
 * 解析视频信息
 */
export async function parseVideo(url) {
  try {
    const { data } = await request.post('/parse', { url })
    if (!data.success) throw new Error(data.error || '解析失败')
    return data.data
  } catch (e) {
    throw new Error(errMsg(e))
  }
}

/**
 * 清除指定链接在当前用户下的解析结果缓存（下次解析将重新走解析流程）
 */
export async function clearParseCache(url) {
  const { data } = await request.post('/parse/cache/clear', { url })
  if (!data.success) throw new Error(data.error || '清除缓存失败')
  return data.data
}

/**
 * QQ 音乐扫码登录：在运行后端的电脑上弹出 Chrome，手机 QQ 扫码后登录态持久化在 sidecar profile 中
 */
export async function qqMusicLogin() {
  const { data } = await request.post('/qqmusic/login', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '登录失败')
  return data.data
}

/**
 * 酷狗扫码登录：在运行后端的电脑上弹出 Chrome，酷狗 APP/手机浏览器扫码后登录态持久化在 sidecar profile 中
 */
export async function kugouLogin() {
  const { data } = await request.post('/kugou/login', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '登录失败')
  return data.data
}

/**
 * 腾讯视频扫码登录：在运行后端的电脑上弹出 Chrome，微信/QQ 扫码后登录态持久化在 sidecar profile 中
 */
export async function tencentLogin() {
  const { data } = await request.post('/tencent/login', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '登录失败')
  return data.data
}

/**
 * QQ 音乐清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，便于更换账号
 */
export async function qqMusicLogout() {
  const { data } = await request.post('/qqmusic/logout', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '清除登录态失败')
  return data.data
}

/**
 * 酷狗清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，便于更换账号
 */
export async function kugouLogout() {
  const { data } = await request.post('/kugou/logout', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '清除登录态失败')
  return data.data
}

/**
 * 腾讯视频清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie，便于更换账号
 */
export async function tencentLogout() {
  const { data } = await request.post('/tencent/logout', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '清除登录态失败')
  return data.data
}

/**
 * 酷播登录：弹出 Chrome 窗口手动登录 VIP 账号（1080p/720p 取流需要）
 */
export async function kubbLogin() {
  const { data } = await request.post('/kubb/login', {}, { timeout: 330000 })
  if (!data.success) throw new Error(data.error || '登录失败')
  return data.data
}

/**
 * 酷播清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie 与 token
 */
export async function kubbLogout() {
  const { data } = await request.post('/kubb/logout', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '清除登录态失败')
  return data.data
}

/**
 * Viu 登录：弹出 Chrome 窗口手动登录 Viu VIP 账号（登录态持久化在 viu-profile）
 */
export async function viuLogin() {
  const { data } = await request.post('/viu/login', {}, { timeout: 340000 })
  if (!data.success) throw new Error(data.error || '登录失败')
  return data.data
}

/**
 * Viu 清除登录态：清空 sidecar 浏览器 profile 中的登录 cookie 与 token
 */
export async function viuLogout() {
  const { data } = await request.post('/viu/logout', {}, { timeout: 300000 })
  if (!data.success) throw new Error(data.error || '清除登录态失败')
  return data.data
}

/**
 * 创建在线播放票据（需登录）。返回 { ticket, mode, mime, title, audioOnly, expiresAt, resumeSeconds, resumeCompleted }
 * 华视影院取流需等待 15~25 秒广告，单独放宽到 60 秒超时。
 */
export async function createPlayTicket({ url, formatId, title }) {
  try {
    const { data } = await request.post('/play/ticket', { url, formatId, title }, { timeout: 60000 })
    if (!data.success) throw new Error(data.error || '创建播放失败')
    return data.data
  } catch (e) {
    throw new Error(errMsg(e))
  }
}

/**
 * 查询某视频的播放进度（需登录）。无记录时返回 null。
 */
export async function getPlayProgress(url) {
  const { data } = await request.get('/play/progress', { params: { url } })
  if (!data.success) throw new Error(data.error || '获取播放进度失败')
  return data.data
}

/**
 * 保存播放进度（需登录）
 */
export async function savePlayProgress(payload) {
  try {
    const { data } = await request.post('/play/progress', payload)
    if (!data.success) throw new Error(data.error || '保存播放进度失败')
    return data.data
  } catch (e) {
    throw new Error(errMsg(e))
  }
}

/**
 * 查询 merge 票据的准备状态（需登录）。返回 { status, progress, error }。
 * 票据过期时后端返回 text/plain 404，这里优先使用响应正文作为错误信息。
 */
export async function getPrepareStatus(ticket) {
  try {
    const { data } = await request.get(`/play/prepare/${ticket}`)
    if (!data.success) throw new Error(data.error || '获取准备状态失败')
    return data.data
  } catch (e) {
    const text = typeof e?.response?.data === 'string' ? e.response.data : ''
    throw new Error(text || errMsg(e))
  }
}

/**
 * 获取直链（直链下载模式）
 */
export async function getDirectUrl(url, formatId) {
  const { data } = await request.post('/direct-url', { url, formatId })
  if (!data.success) throw new Error(data.error || '获取直链失败')
  return data.data
}

/**
 * 服务端代理下载（blob），返回保存文件名。
 * 传入 onProgress 时，内部建立 WebSocket 接收后端推送的下载进度
 */
export async function downloadViaServer({ url, formatId, title, onProgress }) {
  const taskId = onProgress ? crypto.randomUUID() : null
  const ws = taskId ? openProgressWs(taskId, onProgress) : null
  try {
    const resp = await request.post('/download', { url, formatId, title, taskId }, {
      responseType: 'blob',
      // CCTV h5e WASM 解密按 0.6x 实时跑，长视频可能超过 1 小时；
      // 进度已通过 WebSocket 推送，HTTP 请求本身不应超时
      timeout: 0
    })
    const blob = resp.data
    const filename = parseFilename(resp.headers['content-disposition']) ||
      `${title || 'video'}.mp4`
    triggerSave(blob, filename)
    return filename
  } finally {
    if (ws) ws.close()
  }
}

/**
 * 单独下载字幕文件（.vtt）。字幕体积小，无需进度推送。
 * lang 为字幕语言代码（如 zh-Hans），为空时后端按中文优先自动选轨。
 */
export async function downloadSubtitleViaServer({ url, title, lang }) {
  const resp = await request.post('/download-subtitle', { url, title, subtitleLang: lang || '' }, {
    responseType: 'blob',
    timeout: 120000
  })
  const blob = resp.data
  const filename = parseFilename(resp.headers['content-disposition']) ||
    `${title || 'subtitle'}.vtt`
  triggerSave(blob, filename)
  return filename
}

/**
 * 建立下载进度 ws 连接；onProgress({percent, downloaded, total, speed})
 */
function openProgressWs(taskId, onProgress) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const ws = new WebSocket(`${proto}://${location.host}/ws/download-progress?taskId=${taskId}`)
  ws.onmessage = (e) => {
    try {
      const msg = JSON.parse(e.data)
      if (msg.type === 'progress') {
        onProgress({
          downloaded: msg.downloaded,
          total: msg.total,
          speed: msg.speed,
          percent: msg.total > 0 && msg.downloaded >= 0
            ? Math.min(100, Math.round((msg.downloaded / msg.total) * 100))
            : null
        })
      }
    } catch {
      /* ignore */
    }
  }
  return ws
}

export function triggerSave(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10000)
}

export function parseFilename(contentDisposition) {
  if (!contentDisposition) return null
  const utf8 = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1].replace(/%20/g, ' '))
    } catch {
      /* ignore */
    }
  }
  const plain = contentDisposition.match(/filename="?([^";]+)"?/i)
  return plain ? plain[1] : null
}

export { errMsg }
