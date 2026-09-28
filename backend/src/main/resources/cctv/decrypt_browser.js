// CCTV h5e 批量解密 sidecar —— Playwright 浏览器 WASM 版
// 用法: node decrypt_browser.js <variant.m3u8 URL> <outDir> [maxSegments]
// 机制：Node 下载/解复用 TS -> NAL，Playwright 页面上下文 WASM 批量解密，ffmpeg 合并
// 关键：必须在真实 https://tv.cctv.com 页面注入，否则 activeURL 错误导致解密失败
const fs = require('fs');
const path = require('path');
const https = require('https');
const { execFileSync } = require('child_process');
const { chromium } = require('playwright-core');

const WORKER_JS = path.join(__dirname, 'cctv.worker.js');
const FFMPEG = 'D:/develop/ffmpeg/bin/ffmpeg.exe';
const FFPROBE = 'D:/develop/ffmpeg/bin/ffprobe.exe';

const MEDIA_TAG_ID = '_video_player';
const ACTIVE_URL = 'https://tv.cctv.com';
const MEM_EXT = 2048;
const KEY_FNS = ['_CNTV_jsdecVOD7', '_CNTV_jsdecVOD6', '_CNTV_jsdecVOD5', '_CNTV_jsdecVOD4',
  '_CNTV_jsdecVOD3', '_CNTV_jsdecVOD2', '_CNTV_jsdecVOD1', '_CNTV_jsdecVOD0', '_CNTV_jsdecVOD8', '_CNTV_jsdecVOD'];
const VOD_MAP = '0123456';

async function launchBrowser() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('console', msg => { const t = msg.text(); if (!t.includes('parser-blocking')) console.log('[page]', t); });
  page.on('pageerror', err => console.log('[pageerror]', err.message));
  await page.goto('https://tv.cctv.com', { waitUntil: 'domcontentloaded', timeout: 30000 });

  const workerCode = fs.readFileSync(WORKER_JS, 'utf8');
  await page.addScriptTag({ content: workerCode });

  await page.evaluate(({ MEM_EXT, ACTIVE_URL, MEDIA_TAG_ID, KEY_FNS, VOD_MAP }) => {
    return new Promise((resolve, reject) => {
      const loc = { href: 'https://tv.cctv.com/', origin: 'https://tv.cctv.com' };
      const documentShim = { currentScript: null, implementation: undefined, title: '' };
      const navigatorShim = { userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36' };
      const windowShim = {
        AudioContext: function () { return { createMediaElementSource: () => ({ connect: () => {} }) }; },
        webkitAudioContext: undefined, ActiveXObject: undefined
      };
      const selfShim = { location: loc, navigator: navigatorShim };
      const __origEval = window.eval;
      window.eval = function (s) {
        try { const r = __origEval(s); if (typeof r === 'string') return r; return ''; }
        catch (e) { return ''; }
      };
      const dummyRequire = function () { return {}; };
      const CNTVModule = window.CNTVModule;
      if (!CNTVModule) { reject(new Error('CNTVModule not found')); return; }
      const m = CNTVModule({});
      m.onRuntimeInitialized = () => {
        let vmpTag = '';
        let dFlag = false;
        function moduleActive(str, action) {
          const a = m._jsmalloc(str.length + MEM_EXT);
          for (let s = 0; s < str.length + MEM_EXT; s++) m.HEAP8[a + s] = 0;
          for (let o = 0; o < str.length; o++) m.HEAP8[a + o] = str.charCodeAt(o);
          let n = 0;
          if (action === 'init') { vmpTag = ''; n = m._CNTV_InitPlayer(a); }
          else if (action === 'update') {
            n = m._CNTV_UpdatePlayer(a);
            if (n != null && n !== '' && n !== 0) { n = n.toString(16); while (n.length < 8) n = '0' + n; vmpTag = '' + n; }
            n = 0;
          }
          m._jsfree(a);
          return n;
        }
        function moduleDecData(tag, data) {
          const u = data.byteLength;
          const d = m._jsmalloc(u + MEM_EXT);
          m.HEAP8.set(data, d);
          let o = 0;
          if (ACTIVE_URL !== '') { o = ACTIVE_URL.length; for (let i = 0; i < ACTIVE_URL.length; i++) m.HEAP8[d + u + i] = ACTIVE_URL.charCodeAt(i); }
          const c = m._jsmalloc(tag.length + 1);
          for (let i = 0; i < tag.length; i++) m.HEAP8[c + i] = tag.charCodeAt(i);
          m.HEAP8[c + tag.length] = 0;
          if (vmpTag && vmpTag !== '') {
            for (let f = 0; f < vmpTag.length; f++) {
              if (VOD_MAP.includes(vmpTag[f])) m[KEY_FNS[f]](c, d, u, o);
            }
          }
          const l = m[KEY_FNS[8]](c, d, u, o);
          const r = new Uint8Array(l > 0 ? m.HEAP8.buffer.slice(d, d + l) : new ArrayBuffer(0));
          m._jsfree(d); m._jsfree(c);
          return r;
        }
        moduleActive(MEDIA_TAG_ID, 'init');
        moduleActive(MEDIA_TAG_ID, 'update');
        window.eval = __origEval; // 恢复，否则破坏 Playwright evaluate

        // 批量处理 NAL：输入 [{type, dts, dataB64}]，输出 [{type, dataB64} 或 null（种子）]
        function u8ToB64Browser(u8) {
          let s = '';
          const CHUNK = 0x4000;
          for (let i = 0; i < u8.length; i += CHUNK) {
            const end = Math.min(i + CHUNK, u8.length);
            const args = new Array(end - i);
            for (let j = i; j < end; j++) args[j - i] = u8[j];
            s += String.fromCharCode.apply(null, args);
          }
          return btoa(s);
        }
        window.__cntvDecryptBatch = function (nals) {
          const results = [];
          for (const nal of nals) {
            const raw = atob(nal.dataB64);
            const data = new Uint8Array(raw.length);
            for (let i = 0; i < raw.length; i++) data[i] = raw.charCodeAt(i);
            if (nal.type === 25) {
              dFlag = data[1] === 1;
              try { moduleDecData(MEDIA_TAG_ID, data); } catch (e) { }
              results.push(null);
              continue;
            }
            let out = data;
            if ((nal.type === 1 || nal.type === 5) && dFlag) {
              const tag = MEDIA_TAG_ID + '##' + nal.dts + '##0';
              try {
                out = moduleDecData(tag, data);
              } catch (e) {
                // 崩溃时丢该 NAL（与浏览器行为一致）
                results.push(null);
                continue;
              }
            }
            results.push({ type: nal.type, dataB64: u8ToB64Browser(out) });
          }
          return results;
        };
        resolve('ok');
      };
      setTimeout(() => reject(new Error('wasm init timeout')), 30000);
    });
  }, { MEM_EXT, ACTIVE_URL, MEDIA_TAG_ID, KEY_FNS, VOD_MAP });
  console.log('WASM initialized in browser');
  return { browser, page };
}

// tt 分离器逐字移植（vhs_drm2.min.js）：从 PES 负载中切出 NAL
function makeNalSplitter(onNal) {
  let curDts = 0, curPts = 0;
  let t = null, i = 0, e = 0;
  function push(n) {
    let r;
    if (t) { r = new Uint8Array(t.byteLength + n.byteLength); r.set(t); r.set(n, t.byteLength); t = r; }
    else t = n;
    const a = t.byteLength;
    for (; i < a - 3; i++) if (1 === t[i + 2]) { e = i + 5; break; }
    for (; e < a;) switch (t[e]) {
      case 0:
        if (0 !== t[e - 1]) { e += 2; break; }
        if (0 !== t[e - 2]) { e++; break; }
        if (i + 3 !== e - 2) onNal(t.subarray(i + 3, e - 2), curPts, curDts);
        do { e++; } while (1 !== t[e] && e < a);
        i = e - 2; e += 3; break;
      case 1:
        if (0 !== t[e - 1] || 0 !== t[e - 2]) { e += 3; break; }
        onNal(t.subarray(i + 3, e - 2), curPts, curDts);
        i = e - 2; e += 3; break;
      default: e += 3;
    }
    t = t.subarray(i); e -= i; i = 0;
  }
  function flush() { if (t && t.byteLength > 3) onNal(t.subarray(i + 3), curPts, curDts); t = null; i = 0; }
  function pushFrame(es, pts, dts) { curPts = pts; curDts = dts; push(es); }
  return { pushFrame, flush };
}

function parsePts(b, off) {
  return ((b[off] >> 1) & 0x07) * 0x40000000 + (b[off + 1] << 22) + ((b[off + 2] >> 1) << 15) + (b[off + 3] << 7) + (b[off + 4] >> 1);
}
function demuxSegment(buf, state, onPes) {
  for (let pos = 0; pos + 188 <= buf.length; pos += 188) {
    if (buf[pos] !== 0x47) continue;
    const pusi = (buf[pos + 1] & 0x40) !== 0;
    const pid = ((buf[pos + 1] & 0x1f) << 8) | buf[pos + 2];
    const afc = (buf[pos + 3] >> 4) & 3;
    let off = pos + 4;
    if (afc === 2 || afc === 3) off += 1 + buf[pos + 4];
    if (afc === 2 || off >= pos + 188) continue;
    const payload = buf.subarray(off, pos + 188);
    if (pid === 0 && pusi) {
      const tblOff = 1 + payload[0];
      state.pmtPid = ((payload[tblOff + 10] & 0x1f) << 8) | payload[tblOff + 11];
      continue;
    }
    if (pid === state.pmtPid && pusi) {
      const b = payload; const s = 1 + b[0];
      const progInfoLen = ((b[s + 10] & 0x0f) << 8) | b[s + 11];
      let p = s + 12 + progInfoLen;
      const end = s + 3 + (((b[s + 1] & 0x0f) << 8) | b[s + 2]) - 4;
      while (p + 5 <= end) {
        const st = b[p]; const ep = ((b[p + 1] & 0x1f) << 8) | b[p + 2];
        const esInfoLen = ((b[p + 3] & 0x0f) << 8) | b[p + 4];
        if (st === 0x1b || st === 0x24 || st === 0x02) state.videoPid = ep;
        else if (st === 0x0f || st === 0x03 || st === 0x04) state.audioPid = ep;
        p += 5 + esInfoLen;
      }
      continue;
    }
    const kind = pid === state.videoPid ? 'video' : pid === state.audioPid ? 'audio' : null;
    if (!kind) continue;
    if (pusi) {
      if (state.cur) onPes(state.cur);
      if (payload[0] === 0 && payload[1] === 0 && payload[2] === 1) {
        const flags = payload[7];
        const hdrLen = payload[8];
        let pts = 0, dts = 0;
        if (flags & 0x80) pts = parsePts(payload, 9);
        if (flags & 0x40) dts = parsePts(payload, 14); else dts = pts;
        state.cur = { kind, pts, dts, chunks: [payload.subarray(9 + hdrLen)] };
      } else {
        state.cur = { kind, pts: 0, dts: 0, chunks: [payload] };
      }
    } else if (state.cur && state.cur.kind === kind) {
      state.cur.chunks.push(payload);
    }
  }
}
function pesPayload(pes) {
  let len = 0; for (const c of pes.chunks) len += c.length;
  const out = new Uint8Array(len); let o = 0;
  for (const c of pes.chunks) { out.set(c, o); o += c.length; }
  return out;
}

function fetchBuf(url, redirects = 3) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0', 'Referer': 'https://tv.cctv.com/' } }, res => {
      if (res.statusCode >= 300 && res.statusCode < 400 && redirects > 0) return resolve(fetchBuf(res.headers.location, redirects - 1));
      if (res.statusCode !== 200) { reject(new Error('HTTP ' + res.statusCode + ' ' + url)); res.resume(); return; }
      const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => resolve(Buffer.concat(chunks)));
    }).on('error', reject);
  });
}

function u8ToB64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 8192) s += String.fromCharCode.apply(null, u8.subarray(i, i + 8192));
  return Buffer.from(s, 'binary').toString('base64');
}
function b64ToU8(b64) {
  return new Uint8Array(Buffer.from(b64, 'base64'));
}

(async () => {
  const variantUrl = process.argv[2];
  const outDir = process.argv[3] || path.join(__dirname, 'dec-out');
  const maxSegs = parseInt(process.argv[4] || '0', 10);
  fs.mkdirSync(outDir, { recursive: true });
  const PASSTHROUGH = process.env.PASSTHROUGH === '1';

  console.log('[1/5] 启动浏览器 WASM...');
  const { browser, page } = await launchBrowser();

  console.log('[2/5] 下载 m3u8:', variantUrl);
  const playlist = (await fetchBuf(variantUrl)).toString('utf8');
  const base = variantUrl.slice(0, variantUrl.lastIndexOf('/') + 1);
  let segUrls = playlist.split('\n').map(l => l.trim()).filter(l => l && !l.startsWith('#'))
    .map(l => l.startsWith('http') ? l : base + l);
  if (maxSegs > 0) segUrls = segUrls.slice(0, maxSegs);
  console.log('      共', segUrls.length, '段待处理');

  const videoPath = path.join(outDir, 'video.h264');
  const audioPath = path.join(outDir, 'audio.aac');
  const vfd = fs.openSync(videoPath, 'w');
  const afd = fs.openSync(audioPath, 'w');
  const SC = Buffer.from([0, 0, 0, 1]);

  let decCount = 0, dropCount = 0, nalWritten = 0, frameCount = 0;

  const nalQueue = [];
  const splitter = makeNalSplitter((data, pts, dts) => nalQueue.push({ data, type: 31 & data[0], pts, dts }));

  async function drain() {
    if (!nalQueue.length) return;
    // 批量发送当前队列中所有 NAL
    const batch = nalQueue.splice(0, nalQueue.length).map(it => ({
      type: it.type, dts: it.dts, dataB64: u8ToB64(it.data)
    }));
    const results = await page.evaluate(({ batch }) => {
      return window.__cntvDecryptBatch(batch);
    }, { batch });
    for (const r of results) {
      if (!r) { dropCount++; continue; }
      const out = b64ToU8(r.dataB64);
      fs.writeSync(vfd, SC); fs.writeSync(vfd, out);
      nalWritten++;
      if (r.type === 1 || r.type === 5) decCount++;
    }
  }

  console.log('[3/5] 逐段下载+解密...');
  const t0 = Date.now();
  const state = {};
  for (let s = 0; s < segUrls.length; s++) {
    const buf = await fetchBuf(segUrls[s]);
    demuxSegment(buf, state, pes => {
      const payload = pesPayload(pes);
      if (pes.kind === 'video') { splitter.pushFrame(payload, pes.pts, pes.dts); frameCount++; }
      else fs.writeSync(afd, payload);
    });
    await drain();
    if ((s + 1) % 10 === 0 || s === segUrls.length - 1)
      console.log(`      seg ${s + 1}/${segUrls.length} frames=${frameCount} dec=${decCount} drop=${dropCount} ${((Date.now() - t0) / 1000).toFixed(1)}s`);
  }
  if (state.cur) { const payload = pesPayload(state.cur); if (state.cur.kind === 'video') { splitter.pushFrame(payload, state.cur.pts, state.cur.dts); frameCount++; } else fs.writeSync(afd, payload); }
  splitter.flush();
  await drain();
  fs.closeSync(vfd); fs.closeSync(afd);
  console.log(`[4/5] 完成: ${nalWritten} NAL 写出 (${decCount} 解密, ${dropCount} 丢弃), ${frameCount} 帧, 耗时 ${(Date.now() - t0) / 1000}s`);

  if (PASSTHROUGH) { console.log('PASSTHROUGH: skip merge'); await browser.close(); process.exit(0); }
  console.log('[5/5] ffmpeg 合并...');
  const outMp4 = path.join(outDir, 'out.mp4');
  execFileSync(FFMPEG, ['-y', '-f', 'h264', '-framerate', '25', '-i', videoPath, '-i', audioPath,
    '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'copy', '-bsf:a', 'aac_adtstoasc', outMp4], { stdio: 'inherit' });
  execFileSync(FFPROBE, ['-v', 'error', '-show_entries', 'format=duration', '-show_entries', 'stream=codec_name,width,height,r_frame_rate', '-of', 'default=noprint_wrappers=1', outMp4], { stdio: 'inherit' });
  console.log('DONE ->', outMp4);
  await browser.close();
  process.exit(0);
})().catch(e => { console.error('FAIL:', e); process.exit(1); });
