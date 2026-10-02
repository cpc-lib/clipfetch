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
