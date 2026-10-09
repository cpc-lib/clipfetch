<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { appView } from '../stores/app'
import { fetchPreParseList, addPreParse, deletePreParse } from '../api/preparse'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(Number(localStorage.getItem('preparse.size')) || 10)
const savedParsed = localStorage.getItem('preparse.parsed')
const parsed = ref(savedParsed === 'true' ? true : savedParsed === 'false' ? false : '')
const urlFilter = ref('')
const keyword = ref('')
const loading = ref(false)
const errorText = ref('')
const selected = ref(new Set())

// 新增弹窗
const showAdd = ref(false)
const addUrl = ref('')
const addTitle = ref('')
const addLoading = ref(false)
const addError = ref('')

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    const data = await fetchPreParseList({
      page: page.value,
      size: size.value,
      parsed: parsed.value === '' ? undefined : parsed.value,
      url: urlFilter.value.trim() || undefined,
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

function openAdd() {
  addUrl.value = ''
  addTitle.value = ''
  addError.value = ''
  showAdd.value = true
}

async function submitAdd() {
  const url = addUrl.value.trim()
  if (!url) {
    addError.value = '链接不能为空'
    return
  }
  addLoading.value = true
  addError.value = ''
  try {
    await addPreParse({ url, title: addTitle.value.trim() || undefined })
    showAdd.value = false
    page.value = 1
    load()
  } catch (e) {
    addError.value = e.message || '添加失败'
  } finally {
    addLoading.value = false
  }
}

async function removeSelected() {
  if (selected.value.size === 0) return
  if (!confirm(`确认删除选中的 ${selected.value.size} 条记录？`)) return
  try {
    await deletePreParse([...selected.value])
    load()
  } catch (e) {
    errorText.value = e.message || '删除失败'
  }
}

async function removeOne(item) {
  if (!confirm(`确认删除「${item.title || item.url}」？`)) return
  try {
    await deletePreParse([item.id])
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
watch(parsed, search)
watch(size, (v) => localStorage.setItem('preparse.size', String(v)))
watch(parsed, (v) => localStorage.setItem('preparse.parsed', v === '' ? '' : String(v)))

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

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
  <section class="mx-auto max-w-7xl px-4 py-8 sm:px-6">
    <div class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
      <h1 class="text-xl font-bold text-slate-800">预解析库</h1>
      <div class="flex flex-wrap items-center gap-2">
        <button class="btn-primary px-4 py-2 text-sm" @click="openAdd">添加</button>
        <select
          v-model="parsed"
          class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none focus:border-primary"
        >
          <option value="">全部状态</option>
          <option :value="false">未解析</option>
          <option :value="true">已解析</option>
        </select>
        <input
          v-model="urlFilter"
          type="text"
          placeholder="按链接精确查询"
          class="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
          @keydown.enter="search"
        />
        <input
          v-model="keyword"
          type="text"
          placeholder="按名称模糊搜索"
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

    <!-- 新增弹窗 -->
    <div v-if="showAdd" class="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-4" @click.self="showAdd = false">
      <div class="w-full max-w-md rounded-card border border-slate-100 bg-white p-6 shadow-card">
        <h3 class="mb-4 text-base font-semibold text-slate-800">添加预解析链接</h3>
        <div class="space-y-3">
          <div>
            <label class="mb-1 block text-xs text-slate-500">链接 <span class="text-red-400">*</span></label>
            <input
              v-model="addUrl"
              type="text"
              placeholder="粘贴视频链接"
              class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
              @keydown.enter="submitAdd"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs text-slate-500">名称（可空，自动生成）</label>
            <input
              v-model="addTitle"
              type="text"
              placeholder="文件名称"
              class="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm outline-none placeholder:text-slate-300 focus:border-primary"
              @keydown.enter="submitAdd"
            />
          </div>
          <p v-if="addError" class="text-xs text-red-500">{{ addError }}</p>
          <div class="flex justify-end gap-2 pt-1">
            <button class="rounded-lg border border-slate-200 px-4 py-2 text-sm text-slate-600 transition hover:bg-slate-50" @click="showAdd = false">取消</button>
            <button class="btn-primary px-4 py-2 text-sm" :disabled="addLoading" @click="submitAdd">
              {{ addLoading ? '添加中' : '确认添加' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 列表 -->
    <div class="overflow-x-auto rounded-card border border-slate-100 bg-white shadow-card">
      <table class="min-w-full divide-y divide-slate-100 text-sm">
        <thead class="bg-slate-50/60">
          <tr>
            <th class="w-10 px-4 py-3 text-left">
              <input type="checkbox" :checked="list.length > 0 && selected.size === list.length" @change="toggleAll" />
            </th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">名称</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">链接</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">状态</th>
            <th class="px-4 py-3 text-left font-medium text-slate-500">保存时间</th>
            <th class="px-4 py-3 text-right font-medium text-slate-500">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50">
          <tr v-if="list.length === 0 && !loading">
            <td colspan="6" class="px-4 py-10 text-center text-slate-400">暂无数据</td>
          </tr>
          <tr
            v-for="item in list"
            :key="item.id"
            :title="`名称：${item.title || '-'}\n链接：${item.url}`"
            class="transition hover:bg-slate-50/50"
          >
            <td class="px-4 py-3">
              <input type="checkbox" :checked="selected.has(item.id)" @change="toggleSelect(item.id)" />
            </td>
            <td class="max-w-48 truncate px-4 py-3 font-medium text-slate-700">{{ item.title || '-' }}</td>
            <td class="max-w-64 truncate px-4 py-3">
              <button class="text-primary hover:underline" @click="goParse(item.url)">{{ item.url }}</button>
            </td>
            <td class="px-4 py-3">
              <span
                class="inline-flex rounded-full px-2 py-0.5 text-xs font-medium"
                :class="item.parsed ? 'bg-green-50 text-green-600' : 'bg-slate-100 text-slate-500'"
              >{{ item.parsed ? '已解析' : '未解析' }}</span>
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

    <!-- 分页 -->
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
