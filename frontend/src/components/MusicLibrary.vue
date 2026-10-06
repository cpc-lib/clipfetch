<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { appView } from '../stores/app'
import { fetchTempList, deleteTempLinks } from '../api/temp'

// 内嵌在视频下载页时：压缩外边距、隐藏大标题（由外层面板提供标题）
defineProps({
  embedded: { type: Boolean, default: false }
})

const list = ref([])
const total = ref(0)
const page = ref(1)
// 每页条数与下载状态筛选持久化到 localStorage，再次进入页面保持不变
const size = ref(Number(localStorage.getItem('library.size')) || 10)
const savedDownloaded = localStorage.getItem('library.downloaded')
const downloaded = ref(savedDownloaded === 'true' ? true : savedDownloaded === 'false' ? false : '')
const keyword = ref('')
const loading = ref(false)
const errorText = ref('')
const selected = ref(new Set())

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const data = await fetchTempList({
      page: page.value,
      size: size.value,
      downloaded: downloaded.value === '' ? undefined : downloaded.value,
      keyword: keyword.value.trim() || undefined
    })
    list.value = data.records || []
    total.value = data.total || 0
    selected.value = new Set()
  } catch (e) {
    errorText.value = e.message || '查询失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function toggleSelect(id) {
  const s = new Set(selected.value)
  if (s.has(id)) s.delete(id)
  else s.add(id)
  selected.value = s
}

function toggleAll() {
  if (selected.value.size === list.value.length) {
    selected.value = new Set()
  } else {
    selected.value = new Set(list.value.map(i => i.id))
  }
}

async function removeSelected() {
  if (selected.value.size === 0) return
  if (!confirm(`确认删除选中的 ${selected.value.size} 条记录？`)) return
  try {
    await deleteTempLinks([...selected.value])
    load()
  } catch (e) {
    errorText.value = e.message || '删除失败'
  }
}

async function removeOne(item) {
  if (!confirm(`确认删除「${item.title || item.url}」？`)) return
  try {
    await deleteTempLinks([item.id])
    // 删除的是当前页最后一条时回退一页，避免停留在空页
    if (list.value.length === 1 && page.value > 1) {
      page.value--
    } else {
      load()
    }
  } catch (e) {
    errorText.value = e.message || '删除失败'
  }
}

function goParse(url) {
  appView.goParse(url)
}

watch([page, size], load)
watch(downloaded, search)

// 持久化筛选条件（downloaded 可能是 ''/true/false，统一存字符串）
watch(size, (v) => localStorage.setItem('library.size', String(v)))
watch(downloaded, (v) => localStorage.setItem('library.downloaded', v === '' ? '' : String(v)))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

// 生成页码序列：1 ... 当前页前后各2页 ... 末页
const pageNumbers = computed(() => {
  const t = totalPages.value
  const c = page.value
  const pages = []
  if (t <= 7) {
    for (let i = 1; i <= t; i++) pages.push(i)
    return pages
  }
  pages.push(1)
  if (c > 3) pages.push('...')
  for (let i = Math.max(2, c - 2); i <= Math.min(t - 1, c + 2); i++) pages.push(i)
  if (c < t - 2) pages.push('...')
  pages.push(t)
  return pages
})

onMounted(load)
</script>

<template>
  <section :class="embedded ? 'px-2 py-3' : 'mx-auto max-w-7xl px-4 py-8 sm:px-6'">
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <h1 v-if="!embedded" class="text-xl font-bold text-slate-800">文件库</h1>
      <div class="flex flex-wrap items-center gap-2">
        <select
          v-model="downloaded"
          class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-primary"
        >
          <option value="">全部状态</option>
          <option :value="false">未下载</option>
          <option :value="true">已下载</option>
        </select>
        <input
          v-model="keyword"
          type="text"
          placeholder="按文件名搜索"
          class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
          @keydown.enter="search"
        />
        <button class="btn-primary px-4 py-2 text-sm" :disabled="loading" @click="search">
          {{ loading ? '查询中' : '查询' }}
        </button>
        <button
          v-if="selected.size > 0"
          class="rounded-lg border border-red-200 bg-red-50 px-4 py-2 text-sm text-red-500 transition hover:bg-red-100"
          @click="removeSelected"
        >删除 {{ selected.size }} 条</button>
      </div>
    </div>

    <p v-if="errorText" class="mb-4 text-sm text-red-500">{{ errorText }}</p>

    <div class="overflow-x-auto rounded-card border border-slate-100 bg-white shadow-card">
      <table class="min-w-full divide-y divide-slate-100 text-sm">
        <thead class="bg-slate-50/60">
          <tr>
            <th class="w-10 px-4 py-3 text-left">
              <input type="checkbox" :checked="list.length > 0 && selected.size === list.length" @change="toggleAll" />
            </th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">歌曲名</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">链接</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">来源</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">状态</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">保存时间</th>
            <th class="px-4 py-3 text-right font-medium text-slate-500">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-if="list.length === 0 && !loading">
            <td colspan="7" class="px-4 py-10 text-center text-slate-400">暂无数据</td>
          </tr>
          <tr
            v-for="item in list"
            :key="item.id"
            :title="`歌曲名：${item.title || '-'}\n链接：${item.url}\n来源：${item.sourceUrl || '-'}`"
            class="transition hover:bg-slate-50/50"
          >
            <td class="px-4 py-3">
              <input type="checkbox" :checked="selected.has(item.id)" @change="toggleSelect(item.id)" />
            </td>
            <td class="max-w-48 px-4 py-3 font-medium text-slate-700">
              <div class="flex items-center gap-1.5">
                <span class="truncate">{{ item.title || '-' }}</span>
                <span
                  v-if="item.vip === 1"
                  title="VIP 会员歌曲（需对应平台会员才能下载）"
                  class="shrink-0 rounded border border-emerald-500 px-1 text-[10px] font-semibold leading-4 text-emerald-600"
                >VIP</span>
                <span
                  v-else-if="item.vip === 2"
                  title="付费歌曲（需单独购买，会员也无法直接下载）"
                  class="shrink-0 rounded border border-amber-500 px-1 text-[10px] font-semibold leading-4 text-amber-600"
                >付费</span>
              </div>
            </td>
            <td class="max-w-64 truncate px-4 py-3">
              <button class="text-primary hover:underline" @click="goParse(item.url)">{{ item.url }}</button>
            </td>
            <td class="max-w-40 truncate px-4 py-3 text-slate-400">{{ item.sourceUrl || '-' }}</td>
            <td class="px-4 py-3">
              <span
                class="inline-flex rounded-full px-2 py-0.5 text-xs font-medium"
                :class="item.downloaded ? 'bg-green-50 text-green-600' : 'bg-slate-100 text-slate-500'"
              >{{ item.downloaded ? '已下载' : '未下载' }}</span>
            </td>
            <td class="whitespace-nowrap px-4 py-3 text-slate-400">{{ item.createdAt || '-' }}</td>
            <td class="whitespace-nowrap px-4 py-3 text-right">
              <button class="text-primary hover:underline" @click="goParse(item.url)">去解析</button>
              <button class="ml-3 text-red-500 hover:underline" @click="removeOne(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-slate-500">
      <div class="flex items-center gap-2">
        <span>共 {{ total }} 条</span>
        <select
          v-model="size"
          class="rounded-lg border border-slate-200 bg-white px-2 py-1.5 outline-none focus:border-primary"
          @change="page = 1"
        >
          <option :value="10">10 条/页</option>
          <option :value="30">30 条/页</option>
          <option :value="50">50 条/页</option>
          <option :value="100">100 条/页</option>
        </select>
      </div>
      <div class="flex items-center gap-1">
        <button
          class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
          :disabled="page <= 1"
          @click="page--"
        >上一页</button>
        <template v-for="p in pageNumbers" :key="p + '_' + totalPages">
          <span v-if="p === '...'" class="px-1 text-slate-400">…</span>
          <button
            v-else
            class="min-w-8 rounded-lg border px-2 py-1.5 transition"
            :class="p === page
              ? 'border-primary bg-primary text-white'
              : 'border-slate-200 hover:border-primary hover:text-primary'"
            @click="page = p"
          >{{ p }}</button>
        </template>
        <button
          class="rounded-lg border border-slate-200 px-3 py-1.5 transition hover:border-primary disabled:opacity-40"
          :disabled="page >= totalPages"
          @click="page++"
        >下一页</button>
      </div>
    </div>
  </section>
</template>
