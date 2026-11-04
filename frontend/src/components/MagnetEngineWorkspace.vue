<script setup>
import { ref, onMounted, watch, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isLoggedIn, authModal } from '../stores/auth'
import { magnetEngineHealth } from '../api/magnet'
import { nativeHealth } from '../api/nativeTorrent'
import { health as magnetV2Health } from '../api/magnetV2'

// 磁力引擎按需异步加载，大幅降低整体构建体积与初始加载耗时
const MagnetDownloader = defineAsyncComponent(() => import('./MagnetDownloader.vue'))
const NativeTorrentManager = defineAsyncComponent(() => import('./NativeTorrentManager.vue'))
const NativeV2TorrentManager = defineAsyncComponent(() => import('./NativeV2TorrentManager.vue'))

const route = useRoute()
const router = useRouter()

const ENGINES = ['qbittorrent', 'native', 'native-v2']
const initialEngine = (() => {
  const candidate = route?.params?.engine || route?.query?.engine
  return ENGINES.includes(candidate) ? candidate : 'qbittorrent'
})()
const engine = ref(initialEngine)

watch(() => [route?.params?.engine, route?.query?.engine], ([pEngine, qEngine]) => {
  const candidate = ENGINES.includes(pEngine) ? pEngine : (ENGINES.includes(qEngine) ? qEngine : null)
  if (candidate) {
    engine.value = candidate
  }
})

function setEngine(target) {
  engine.value = target
  if (router && (route?.params?.engine !== target)) {
    router.replace({ path: `/magnet/${target}` })
  }
}
const checks = ref({ qbittorrent: 'checking', native: 'checking', nativeV2: 'checking' })
const nativeFlags = ref(null)
const statusMessage = ref('')
let generation = 0

async function refreshEngines() {
  const token = ++generation
  if (!isLoggedIn.value) {
    checks.value = { qbittorrent: 'login', native: 'login', nativeV2: 'login' }
    nativeFlags.value = null
    return
  }
  checks.value = { qbittorrent: 'checking', native: 'checking', nativeV2: 'checking' }
  statusMessage.value = ''
  const [qbt, native, v2] = await Promise.allSettled([
    magnetEngineHealth(), nativeHealth(), magnetV2Health()
  ])
  if (token !== generation) return
  checks.value = {
    qbittorrent: qbt.status === 'fulfilled' && qbt.value?.version && qbt.value?.connected !== false
      ? 'ready' : 'unavailable',
    native: native.status === 'fulfilled' && native.value?.configuredEnabled &&
      native.value?.taskControlEnabled && native.value?.taskCreationEnabled
      ? 'ready' : 'unavailable',
    nativeV2: v2.status === 'fulfilled' && v2.value?.configured && v2.value?.connected
      ? 'ready' : 'unavailable'
  }
  nativeFlags.value = native.status === 'fulfilled' ? native.value : null
  if (!route?.params?.engine && !route?.query?.engine) {
    if (checks.value.qbittorrent === 'unavailable' && checks.value.native === 'ready') {
      engine.value = 'native'
    } else if (checks.value.qbittorrent === 'unavailable' && checks.value.native === 'unavailable'
      && checks.value.nativeV2 === 'ready') {
      engine.value = 'native-v2'
    }
  }
  if (qbt.status === 'rejected' && native.status === 'rejected' && v2.status === 'rejected') {
    statusMessage.value = '所有引擎的状态检查均失败，请确认后端服务和登录状态'
  }
}
watch(isLoggedIn, refreshEngines)
onMounted(refreshEngines)
</script>

<template>
  <section class="mx-auto max-w-7xl px-4 pt-6 sm:px-6">
    <div class="mb-5 rounded-card border border-slate-100 bg-white p-4 shadow-card">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 class="text-xl font-bold text-slate-800">磁力下载</h1>
          <p class="mt-1 text-sm text-slate-500">按任务选择下载引擎，任务和进度分别由对应后端管理。</p>
        </div>
        <button type="button" class="rounded-lg border border-slate-200 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50"
                @click="refreshEngines">刷新引擎状态</button>
      </div>
      <div class="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <button type="button" class="rounded-xl border p-4 text-left transition"
                :class="engine === 'qbittorrent' ? 'border-primary bg-primary-light/30' : 'border-slate-200 hover:border-primary/40'"
                :aria-pressed="engine === 'qbittorrent'"
                @click="setEngine('qbittorrent')">
          <div class="flex items-center justify-between gap-2">
            <span class="font-semibold text-slate-800">qBittorrent</span>
            <span class="text-xs" :class="checks.qbittorrent === 'ready' ? 'text-emerald-600' : 'text-slate-500'">
              {{ { ready: '已连接', unavailable: '暂不可用', checking: '检查中', login: '请登录' }[checks.qbittorrent] }}
            </span>
          </div>
          <p class="mt-2 text-xs text-slate-500">成熟下载链路 · Magnet 解析、文件选择、下载任务及文件提取</p>
        </button>
        <button type="button" class="rounded-xl border p-4 text-left transition"
                :class="engine === 'native' ? 'border-primary bg-primary-light/30' : 'border-slate-200 hover:border-primary/40'"
                :aria-pressed="engine === 'native'"
                @click="setEngine('native')">
          <div class="flex items-center justify-between gap-2">
            <span class="font-semibold text-slate-800">Native BitTorrent（实验）</span>
            <span class="text-xs" :class="checks.native === 'ready' ? 'text-emerald-600' : 'text-amber-600'">
              {{ { ready: '实验入口可用', unavailable: '未启用或不可用', checking: '检查中', login: '请登录' }[checks.native] }}
            </span>
          </div>
          <p class="mt-2 text-xs text-slate-500">当前通过 .torrent 创建任务 · 有界 Piece 下载，尚非完整 Magnet 替代</p>
        </button>
        <button type="button" class="rounded-xl border p-4 text-left transition"
                :class="engine === 'native-v2' ? 'border-primary bg-primary-light/30' : 'border-slate-200 hover:border-primary/40'"
                :aria-pressed="engine === 'native-v2'"
                @click="setEngine('native-v2')">
          <div class="flex items-center justify-between gap-2">
            <span class="font-semibold text-slate-800">Native v2（新引擎）</span>
            <span class="text-xs" :class="checks.nativeV2 === 'ready' ? 'text-emerald-600' : 'text-amber-600'">
              {{ { ready: 'Worker 已连接', unavailable: '未启用或不可用', checking: '检查中', login: '请登录' }[checks.nativeV2] }}
            </span>
          </div>
          <p class="mt-2 text-xs text-slate-500">完整 Magnet 解析 · 文件选择 · 断点续传 · 流式下载</p>
        </button>
      </div>
      <p v-if="statusMessage" role="alert" class="mt-3 text-sm text-amber-700">{{ statusMessage }}</p>
      <div v-if="!isLoggedIn" class="mt-3 text-sm text-slate-600">
        使用下载任务需要先登录。
        <button class="font-medium text-primary underline" @click="authModal.open('login')">立即登录</button>
      </div>
      <p v-else-if="engine === 'native' && checks.native === 'unavailable'"
         class="mt-3 text-sm text-amber-700" role="status">
        原生任务功能未启用或状态检查失败。请确认服务器的 Native 功能开关；qBittorrent 不受影响。
      </p>
      <p v-else-if="engine === 'native' && nativeFlags && !nativeFlags.asyncWorkerEnabled"
         class="mt-3 text-sm text-amber-700">
        原生 Worker 当前不可运行，可查看已有任务；需要管理员开启对应实验功能后才能启动下载。
      </p>
      <p v-else-if="engine === 'native-v2' && checks.nativeV2 === 'unavailable'"
         class="mt-3 text-sm text-amber-700" role="status">
        Native v2 未启用或 Worker 未连接。请确认服务端开关、Worker 容器与证书配置；其他引擎不受影响。
      </p>
    </div>
  </section>
  <MagnetDownloader v-if="engine === 'qbittorrent'" />
  <NativeTorrentManager v-else-if="engine === 'native'" />
  <NativeV2TorrentManager v-else />
</template>
