<script setup>
import { ref, watch } from 'vue'
import { cookieModal } from '../stores/cookies'
import { listCookies, uploadCookie, deleteCookie, errMsg } from '../api/cookies'

const list = ref([])
const loading = ref(false)
const loadError = ref('')
/** 每个平台独立的上传/删除状态：{ douyin: { uploading, error, success }, ... } */
const busy = ref({})
const hints = ref({})

watch(() => cookieModal.visible, (v) => {
  if (v) refresh()
})

async function refresh() {
  loading.value = true
  loadError.value = ''
  try {
    list.value = await listCookies()
  } catch (e) {
    loadError.value = errMsg(e)
  } finally {
    loading.value = false
  }
}

function pickFile(platform) {
  hints.value[platform] = ''
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.txt,text/plain'
  input.onchange = () => {
    if (input.files && input.files[0]) doUpload(platform, input.files[0])
  }
  input.click()
}

async function doUpload(platform, file) {
  busy.value[platform] = { uploading: true }
  hints.value[platform] = ''
  try {
    const updated = await uploadCookie(platform, file)
    const idx = list.value.findIndex((c) => c.platform === platform)
    if (idx >= 0) list.value[idx] = updated
    hints.value[platform] = { type: 'ok', text: '保存成功' }
  } catch (e) {
    hints.value[platform] = { type: 'error', text: errMsg(e) }
  } finally {
    busy.value[platform] = { uploading: false }
  }
}

async function remove(platform) {
  busy.value[platform] = { deleting: true }
  hints.value[platform] = ''
  try {
    await deleteCookie(platform)
    await refresh()
  } catch (e) {
    hints.value[platform] = { type: 'error', text: errMsg(e) }
  } finally {
    busy.value[platform] = { deleting: false }
  }
}

function fmtTime(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="cookieModal.visible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-sm"
      @click.self="cookieModal.close()"
    >
      <div class="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-card bg-white p-6 shadow-xl sm:p-8">
        <div class="flex items-start justify-between">
          <div>
            <h3 class="text-xl font-bold text-slate-800">我的 Cookies</h3>
            <p class="mt-1 text-sm text-slate-400">
              抖音 / Instagram 必须配置才能下载；YouTube / Twitter / TikTok / Bilibili 可选，用于会员或限流内容，过期后在这里更新即可
            </p>
          </div>
          <button class="rounded-lg px-2 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600" @click="cookieModal.close()">
            <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M6 6l12 12M18 6L6 18" stroke-linecap="round" />
            </svg>
          </button>
        </div>

        <div class="mt-4 rounded-xl bg-slate-50 p-3.5 text-xs leading-relaxed text-slate-500">
          <p class="font-medium text-slate-600">如何获取 cookies.txt？</p>
          <ol class="mt-1 list-decimal space-y-0.5 pl-4">
            <li>在电脑浏览器安装扩展「Get cookies.txt LOCALLY」</li>
            <li>登录目标平台后，在该平台页面点击扩展 → Export</li>
            <li>把导出的 .txt 文件在下方对应平台上传即可</li>
          </ol>
        </div>

        <p v-if="loading" class="py-8 text-center text-sm text-slate-400">加载中…</p>
        <p v-else-if="loadError" class="mt-3 text-sm text-red-500">{{ loadError }}</p>

        <div v-else class="mt-4 space-y-3">
          <div
            v-for="c in list"
            :key="c.platform"
            class="rounded-2xl border p-4"
            :class="!c.configured ? 'border-slate-200' : c.valid ? 'border-emerald-200 bg-emerald-50/40' : 'border-red-200 bg-red-50/40'"
          >
            <div class="flex items-center justify-between gap-3">
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <span class="text-sm font-semibold text-slate-800">{{ c.platformName }}</span>
                  <span
                    v-if="c.configured"
                    class="rounded-full px-2 py-0.5 text-[10px] font-medium"
                    :class="c.valid ? 'bg-emerald-100 text-emerald-700' : 'bg-red-100 text-red-600'"
                  >
                    {{ c.valid ? '有效' : '已失效' }}
                  </span>
                  <span v-else class="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-medium text-slate-500">
                    未配置
                  </span>
                </div>
                <p class="mt-1 truncate text-xs text-slate-500">
                  {{ c.statusMessage || (c.configured ? '' : c.required ? '尚未上传 cookies，该平台暂不可用' : '未配置（可选，匿名也可下载公开内容）') }}
                </p>
                <p v-if="c.configured && c.lastVerifiedAt" class="mt-0.5 text-[10px] text-slate-400">
                  校验时间：{{ fmtTime(c.lastVerifiedAt) }}
                </p>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <button
                  class="rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-white transition hover:opacity-90 disabled:opacity-50"
                  :disabled="busy[c.platform]?.uploading || busy[c.platform]?.deleting"
                  @click="pickFile(c.platform)"
                >
                  {{ busy[c.platform]?.uploading ? '上传中…' : c.configured ? '更新' : '上传' }}
                </button>
                <button
                  v-if="c.configured"
                  class="rounded-lg border border-slate-200 px-2.5 py-1.5 text-xs text-slate-500 transition hover:border-red-300 hover:text-red-500 disabled:opacity-50"
                  :disabled="busy[c.platform]?.uploading || busy[c.platform]?.deleting"
                  @click="remove(c.platform)"
                >
                  删除
                </button>
              </div>
            </div>
            <p v-if="hints[c.platform]" class="mt-2 text-xs" :class="hints[c.platform].type === 'error' ? 'text-red-500' : 'text-emerald-600'">
              {{ hints[c.platform].text }}
            </p>
          </div>
        </div>

        <p class="mt-4 text-center text-[11px] text-slate-400">
          cookies 仅保存在本站数据库中，用于代替你完成平台请求，不会公开给其他用户
        </p>
      </div>
    </div>
  </Teleport>
</template>
