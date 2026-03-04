/**
 * AI 视频总结 API 封装
 * 使用原生 fetch + ReadableStream 处理 SSE 流式响应
 */

function parseSSELine(line) {
  if (line.startsWith('event:')) return { type: 'event', value: line.slice(6).trim() }
  if (line.startsWith('data:')) {
    let value = line.slice(5)
    if (value.startsWith(' ')) value = value.slice(1)
    return { type: 'data', value }
  }
  return null
}

async function handleSSEStream(response, callbacks) {
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''

    for (const line of lines) {
      const trimmed = line.trim()
      if (!trimmed) {
        currentEvent = ''
        continue
      }
      if (trimmed.startsWith(':')) continue

      const parsed = parseSSELine(trimmed)
      if (!parsed) continue

      if (parsed.type === 'event') {
        currentEvent = parsed.value
      } else if (parsed.type === 'data') {
        const handler = callbacks[currentEvent]
        if (handler) handler(parsed.value)
      }
    }
  }
}

export async function summarizeVideo(url, language = 'zh', callbacks = {}) {
  const response = await fetch('/api/summarize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, language }),
  })

  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`)
  }

  await handleSSEStream(response, callbacks)
}

export async function chatWithVideo(url, question, subtitleText = '', callbacks = {}) {
  const response = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, question, subtitle_text: subtitleText }),
  })

  if (!response.ok) {
    throw new Error(`请求失败: ${response.status}`)
  }

  await handleSSEStream(response, callbacks)
}
