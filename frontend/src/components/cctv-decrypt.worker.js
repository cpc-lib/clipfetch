/* CCTV h5e 分片解密 Worker：TS 解复用 -> NAL 解密 -> 原 TS 负载写回。 */
const MEDIA_TAG_ID = '_video_player'
const ACTIVE_URL = 'https://tv.cctv.com'
const MEM_EXT = 2048
const KEY_FNS = ['_CNTV_jsdecVOD7', '_CNTV_jsdecVOD6', '_CNTV_jsdecVOD5', '_CNTV_jsdecVOD4',
  '_CNTV_jsdecVOD3', '_CNTV_jsdecVOD2', '_CNTV_jsdecVOD1', '_CNTV_jsdecVOD0', '_CNTV_jsdecVOD8', '_CNTV_jsdecVOD']
const VOD_MAP = '0123456'

let m = null
let vmpTag = ''
let dFlag = false
const streamInfo = {}

self.onmessage = (event) => {
  const msg = event.data
  if (msg.type === 'init') {
    try {
      // 只将 WASM 的固定配置请求改为票据同源地址，其余 XHR 保持原样
      const configUrl = msg.scriptUrl.replace(/cctv\.worker\.js$/, 'H5player.json')
      const xhrOpen = self.XMLHttpRequest.prototype.open
      self.XMLHttpRequest.prototype.open = function (method, url, ...args) {
        const target = String(url) === ACTIVE_URL + '/Library/H5player.json' ? configUrl : url
        return xhrOpen.call(this, method, target, ...args)
      }
      // WASM 依据页面位置初始化解密上下文，不能沿用 Worker 的 localhost/blob location
      const activeLocation = new URL(ACTIVE_URL + '/')
      Object.defineProperty(self, 'location', { value: activeLocation, configurable: true })
      self.window = self
      self.document = {
        currentScript: null,
        location: activeLocation,
        URL: activeLocation.href,
        documentURI: activeLocation.href,
        domain: activeLocation.hostname,
        referrer: ''
      }
      importScripts(msg.scriptUrl)
      const instance = self.CNTVModule({})
      instance.onRuntimeInitialized = () => {
        m = instance
        moduleActive(MEDIA_TAG_ID, 'init')
        moduleActive(MEDIA_TAG_ID, 'update')
        self.postMessage({ type: 'ready' })
      }
    } catch (e) {
      self.postMessage({ type: 'initError', error: String(e.message || e) })
    }
    return
  }
  if (msg.type !== 'decrypt') return
  try {
    const data = new Uint8Array(msg.buffer)
    decryptSegment(data)
    self.postMessage({ type: 'result', id: msg.id, buffer: data.buffer }, [data.buffer])
  } catch (e) {
    self.postMessage({ type: 'error', id: msg.id, error: String(e.message || e) })
  }
}

function moduleActive(str, action) {
  const a = m._jsmalloc(str.length + MEM_EXT)
  for (let s = 0; s < str.length + MEM_EXT; s++) m.HEAP8[a + s] = 0
  for (let o = 0; o < str.length; o++) m.HEAP8[a + o] = str.charCodeAt(o)
  let n = 0
  if (action === 'init') {
    vmpTag = ''
    n = m._CNTV_InitPlayer(a)
  } else {
    n = m._CNTV_UpdatePlayer(a)
    if (n != null && n !== '' && n !== 0) {
      n = n.toString(16)
      while (n.length < 8) n = '0' + n
      vmpTag = '' + n
    }
    n = 0
  }
  m._jsfree(a)
  return n
}

function moduleDecData(tag, data) {
  const u = data.byteLength
  const d = m._jsmalloc(u + MEM_EXT)
  m.HEAP8.set(data, d)
  let o = 0
  if (ACTIVE_URL !== '') {
    o = ACTIVE_URL.length
    for (let i = 0; i < ACTIVE_URL.length; i++) m.HEAP8[d + u + i] = ACTIVE_URL.charCodeAt(i)
  }
  const c = m._jsmalloc(tag.length + 1)
  for (let i = 0; i < tag.length; i++) m.HEAP8[c + i] = tag.charCodeAt(i)
  m.HEAP8[c + tag.length] = 0
  if (vmpTag) {
    for (let f = 0; f < vmpTag.length; f++) {
      if (VOD_MAP.includes(vmpTag[f])) m[KEY_FNS[f]](c, d, u, o)
    }
  }
  const l = m[KEY_FNS[8]](c, d, u, o)
  const r = new Uint8Array(l > 0 ? m.HEAP8.buffer.slice(d, d + l) : new ArrayBuffer(0))
  m._jsfree(d)
  m._jsfree(c)
  return r
}

function decryptNal(tag, data) {
  try {
    return moduleDecData(tag, data)
  } catch (firstError) {
    for (const align of [4, 8, 16, 32]) {
      const padLen = (align - (data.length % align)) % align
      if (padLen === 0) continue
      try {
        const padded = new Uint8Array(data.length + padLen)
        padded.set(data)
        return moduleDecData(tag, padded)
      } catch {
        /* 继续尝试下一档对齐 */
      }
    }
    throw new Error('CCTV 视频分片解密失败：' + String(firstError.message || firstError))
  }
}

function decryptSegment(buf) {
  const pesList = []
  demuxSegment(buf, pesList)
  const chunks = []
  let total = 0
  for (const pes of pesList) {
    if (pes.kind !== 'video') continue
    for (const data of pes.chunks) {
      chunks.push({ start: total, data })
      total += data.length
    }
  }
  const nals = []
  const splitter = makeNalSplitter((data, pts, dts, start, end) => {
    if (data.length) nals.push({ data, type: data[0] & 31, dts, start, end })
  })
  for (const pes of pesList) {
    if (pes.kind === 'video') splitter.pushFrame(pesPayload(pes), pes.pts, pes.dts)
  }
  splitter.flush()

  for (const nal of nals) {
    moduleActive(MEDIA_TAG_ID, 'update')
    if (nal.type === 25) {
      dFlag = nal.data[1] === 1
      try { moduleDecData(MEDIA_TAG_ID, nal.data) } catch { /* 与整片解密一致，忽略种子异常 */ }
      continue
    }
    if ((nal.type !== 1 && nal.type !== 5) || !dFlag) continue
    let out = decryptNal(MEDIA_TAG_ID + '##' + nal.dts + '##0', nal.data)
    if (!out.length) throw new Error('CCTV 视频分片解密结果为空')
    const capacity = nal.end - nal.start
    if (out.length > capacity) {
      for (let i = capacity; i < out.length; i++) {
        if (out[i] !== 0) throw new Error('CCTV 解密后数据超出原分片空间')
      }
      out = out.subarray(0, capacity)
    }
    writeChunks(chunks, nal.start, out)
    // H.264 Annex B 允许 NAL 后的 trailing_zero_8bits，保持原 PES/TS 长度不变
    fillChunks(chunks, nal.start + out.length, nal.start + capacity)
  }
}

function writeChunks(chunks, start, value) {
  const end = start + value.length
  for (const chunk of chunks) {
    const from = Math.max(start, chunk.start)
    const to = Math.min(end, chunk.start + chunk.data.length)
    if (from < to) chunk.data.set(value.subarray(from - start, to - start), from - chunk.start)
  }
}

function fillChunks(chunks, start, end) {
  for (const chunk of chunks) {
    const from = Math.max(start, chunk.start)
    const to = Math.min(end, chunk.start + chunk.data.length)
    if (from < to) chunk.data.fill(0, from - chunk.start, to - chunk.start)
  }
}

// tt 分离器逐字移植（vhs_drm2.min.js），额外记录 NAL 在视频 ES 中的字节区间
function makeNalSplitter(onNal) {
  let curDts = 0, curPts = 0, base = 0
  let t = null, i = 0, e = 0
  function push(n) {
    let r
    if (t) {
      r = new Uint8Array(t.byteLength + n.byteLength)
      r.set(t)
      r.set(n, t.byteLength)
      t = r
    } else t = n
    const a = t.byteLength
    for (; i < a - 3; i++) if (t[i + 2] === 1) { e = i + 5; break }
    for (; e < a;) switch (t[e]) {
      case 0:
        if (t[e - 1] !== 0) { e += 2; break }
        if (t[e - 2] !== 0) { e++; break }
        if (i + 3 !== e - 2) onNal(t.subarray(i + 3, e - 2), curPts, curDts, base + i + 3, base + e - 2)
        do { e++ } while (t[e] !== 1 && e < a)
        i = e - 2
        e += 3
        break
      case 1:
        if (t[e - 1] !== 0 || t[e - 2] !== 0) { e += 3; break }
        onNal(t.subarray(i + 3, e - 2), curPts, curDts, base + i + 3, base + e - 2)
        i = e - 2
        e += 3
        break
      default: e += 3
    }
    t = t.subarray(i)
    base += i
    e -= i
    i = 0
  }
  function flush() {
    if (t && t.byteLength > 3) onNal(t.subarray(i + 3), curPts, curDts, base + i + 3, base + t.byteLength)
    t = null
    i = 0
  }
  return { pushFrame(es, pts, dts) { curPts = pts; curDts = dts; push(es) }, flush }
}

function parsePts(b, off) {
  return ((b[off] >> 1) & 0x07) * 0x40000000 + (b[off + 1] << 22) +
    ((b[off + 2] >> 1) << 15) + (b[off + 3] << 7) + (b[off + 4] >> 1)
}

function demuxSegment(buf, out) {
  let cur = null
  const flush = () => { if (cur) out.push(cur); cur = null }
  for (let pos = 0; pos + 188 <= buf.length; pos += 188) {
    if (buf[pos] !== 0x47) continue
    const pusi = (buf[pos + 1] & 0x40) !== 0
    const pid = ((buf[pos + 1] & 0x1f) << 8) | buf[pos + 2]
    const afc = (buf[pos + 3] >> 4) & 3
    let off = pos + 4
    if (afc === 2 || afc === 3) off += 1 + buf[pos + 4]
    if (afc === 2 || off >= pos + 188) continue
    const payload = buf.subarray(off, pos + 188)
    if (pid === 0 && pusi) {
      const t = 1 + payload[0]
      streamInfo.pmtPid = ((payload[t + 10] & 0x1f) << 8) | payload[t + 11]
      continue
    }
    if (pid === streamInfo.pmtPid && pusi) {
      const s = 1 + payload[0]
      const progInfoLen = ((payload[s + 10] & 0x0f) << 8) | payload[s + 11]
      let p = s + 12 + progInfoLen
      const end = s + 3 + (((payload[s + 1] & 0x0f) << 8) | payload[s + 2]) - 4
      while (p + 5 <= end) {
        const st = payload[p]
        const ep = ((payload[p + 1] & 0x1f) << 8) | payload[p + 2]
        const esInfoLen = ((payload[p + 3] & 0x0f) << 8) | payload[p + 4]
        if (st === 0x1b || st === 0x24 || st === 0x02) streamInfo.videoPid = ep
        else if (st === 0x0f || st === 0x03 || st === 0x04) streamInfo.audioPid = ep
        p += 5 + esInfoLen
      }
      continue
    }
    const kind = pid === streamInfo.videoPid ? 'video' : pid === streamInfo.audioPid ? 'audio' : null
    if (!kind) continue
    if (pusi) {
      flush()
      if (payload[0] === 0 && payload[1] === 0 && payload[2] === 1) {
        const flags = payload[7]
        const hdrLen = payload[8]
        let pts = 0, dts = 0
        if (flags & 0x80) pts = parsePts(payload, 9)
        dts = flags & 0x40 ? parsePts(payload, 14) : pts
        cur = { kind, pts, dts, chunks: [payload.subarray(9 + hdrLen)] }
      } else cur = { kind, pts: 0, dts: 0, chunks: [payload] }
    } else if (cur && cur.kind === kind) cur.chunks.push(payload)
  }
  flush()
}

function pesPayload(pes) {
  let len = 0
  for (const c of pes.chunks) len += c.length
  const out = new Uint8Array(len)
  let o = 0
  for (const c of pes.chunks) { out.set(c, o); o += c.length }
  return out
}
