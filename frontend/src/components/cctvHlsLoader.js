// hls.js 分片 loader：网络请求完成后进入 Web Worker 解密，再按原 TS 格式交给转封装器。
export function createCctvHlsLoader(Hls, ticket) {
  const worker = new Worker(new URL('./cctv-decrypt.worker.js', import.meta.url))
  const pending = new Map()
  let nextId = 1
  let destroyed = false
  let queue = Promise.resolve()
  let readyResolve
  let readyReject
  const ready = new Promise((resolve, reject) => {
    readyResolve = resolve
    readyReject = reject
  })

  worker.onmessage = (event) => {
    const msg = event.data
    if (msg.type === 'ready') {
      readyResolve()
      return
    }
    if (msg.type === 'initError') {
      readyReject(new Error('CCTV 解密组件初始化失败：' + msg.error))
      return
    }
    const request = pending.get(msg.id)
    if (!request) return
    pending.delete(msg.id)
    if (msg.type === 'result') request.resolve(msg.buffer)
    else request.reject(new Error(msg.error || 'CCTV 分片解密失败'))
  }
  worker.onerror = (event) => {
    const error = new Error(event.message || 'CCTV 解密 Worker 运行失败')
    readyReject(error)
    for (const request of pending.values()) request.reject(error)
    pending.clear()
  }
  worker.postMessage({
    type: 'init',
    scriptUrl: `/api/play/hls/${encodeURIComponent(ticket)}/cctv.worker.js`
  })

  function decrypt(buffer) {
    const run = queue.then(async () => {
      await ready
      if (destroyed) throw new Error('播放器已关闭')
      return new Promise((resolve, reject) => {
        const id = nextId++
        pending.set(id, { resolve, reject })
        worker.postMessage({ type: 'decrypt', id, buffer }, [buffer])
      })
    })
    queue = run.catch(() => {})
    return run
  }

  class CctvFragmentLoader {
    constructor(config) {
      this.loader = new Hls.DefaultConfig.loader(config)
      this.closed = false
    }

    get stats() {
      return this.loader.stats
    }

    load(context, config, callbacks) {
      if (context.frag?.sn === 'initSegment') {
        this.loader.load(context, config, callbacks)
        return
      }
      const wrapped = {
        ...callbacks,
        // 加密字节不能进入渐进转封装，完整解密后再一次性回调
        onProgress: undefined,
        onSuccess: (response, stats, ctx, networkDetails) => {
          decrypt(response.data).then((buffer) => {
            if (this.closed || stats.aborted) return
            callbacks.onSuccess({ ...response, data: buffer }, stats, ctx, networkDetails)
          }).catch((e) => {
            if (this.closed || stats.aborted) return
            callbacks.onError({ code: 0, text: e.message || 'CCTV 分片解密失败' }, ctx, networkDetails, stats)
          })
        }
      }
      this.loader.load(context, config, wrapped)
    }

    abort() {
      this.loader.abort()
    }

    destroy() {
      this.closed = true
      this.loader.destroy()
    }
  }

  return {
    Loader: CctvFragmentLoader,
    destroy() {
      destroyed = true
      worker.terminate()
      const error = new Error('播放器已关闭')
      readyReject(error)
      for (const request of pending.values()) request.reject(error)
      pending.clear()
    }
  }
}
