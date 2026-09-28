import { authState } from '../stores/auth'

/**
 * 解析 SSE 流（fetch + ReadableStream）
 * 事件块：event: xxx \n data: ... \n data: ... \n \n
 * summary/answer 的 data 为 JSON 编码字符串，先 JSON.parse 再派发
 */
async function streamSSE(url, body, handlers, signal) {
  let resp
  try {
    resp = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(authState.accessToken ? { Authorization: `Bearer ${authState.accessToken}` } : {})
      },
      body: JSON.stringify(body),
      signal
    })
  } catch (e) {
    handlers.onError?.('网络异常，请稍后重试')
    return
  }

  if (!resp.ok) {
    let message = '请求失败'
    try {
      const data = await resp.json()
      if (data.error) message = data.error
    } catch {
      /* ignore */
    }
    if (resp.status === 401) {
      handlers.onUnauthorized?.(message)
      return
    }
    handlers.onError?.(message)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (eventName, dataPayload) => {
    switch (eventName) {
      case 'summary':
      case 'answer': {
        try {
          const parsed = JSON.parse(dataPayload)
          handlers.onToken?.(parsed.content ?? '')
        } catch {
          handlers.onToken?.(dataPayload)
        }
        break
      }
      case 'subtitle':
        handlers.onSubtitle?.(safeJson(dataPayload))
        break
      case 'summary_done':
        handlers.onSummaryDone?.(safeJson(dataPayload))
        break
      case 'mindmap':
        handlers.onMindmap?.(safeJson(dataPayload))
        break
      case 'done':
        handlers.onDone?.(safeJson(dataPayload))
        break
      case 'error': {
        const parsed = safeJson(dataPayload)
        handlers.onError?.(parsed?.message || 'AI 服务异常')
        break
      }
      default:
        break
    }
  }

  const processBlock = (block) => {
    const lines = block.split('\n')
    let eventName = 'message'
    const dataLines = []
    for (const line of lines) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5))
      }
    }
    if (dataLines.length > 0 || eventName !== 'message') {
      dispatch(eventName, dataLines.join('\n'))
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 2)
        if (block.trim()) processBlock(block)
      }
    }
    if (buffer.trim()) processBlock(buffer)
  } catch (e) {
    if (e.name !== 'AbortError') {
      handlers.onError?.('连接中断，请重试')
    }
  }
}

function safeJson(str) {
  try {
    return JSON.parse(str)
  } catch {
    return str
  }
}

export function summarize(url, handlers, signal) {
  return streamSSE('/api/summarize', { url }, handlers, signal)
}

export function chat({ url, question, subtitleText }, handlers, signal) {
  return streamSSE('/api/chat', { url, question, subtitleText }, handlers, signal)
}

export { streamSSE }
