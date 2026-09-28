<script setup>
const plans = [
  {
    name: '免费版',
    price: '¥0',
    unit: '/永久',
    highlight: false,
    desc: '满足日常下载需求',
    features: [
      { text: '视频解析下载（3 次 AI 总结/天）', ok: true },
      { text: '多种清晰度选择', ok: true },
      { text: '抖音无水印下载', ok: true },
      { text: '字幕文件导出', ok: true },
      { text: 'AI 总结每日 3 次', ok: true }
    ],
    action: '免费开始',
    tag: ''
  },
  {
    name: '高级版 VIP',
    price: '¥29',
    unit: '/月',
    highlight: true,
    desc: '解锁全部进阶能力',
    features: [
      { text: 'AI 总结 / 思维导图无限次', ok: true },
      { text: '批量下载（即将上线）', ok: true },
      { text: '4K 原画画质优先', ok: true },
      { text: '字幕翻译（即将上线）', ok: true },
      { text: '下载任务优先队列', ok: true }
    ],
    action: '开通 VIP',
    tag: '最受欢迎'
  }
]
</script>

<template>
  <section id="pricing" class="bg-slate-50/70 py-16">
    <div class="mx-auto max-w-7xl px-4 sm:px-6">
      <h2 class="text-center text-2xl font-bold text-slate-900 sm:text-3xl">会员套餐</h2>
      <p class="mt-2 text-center text-sm text-slate-400">免费好用，升级更爽</p>
      <div class="mx-auto mt-10 grid max-w-3xl grid-cols-1 gap-6 sm:grid-cols-2">
        <div
          v-for="p in plans"
          :key="p.name"
          class="relative rounded-card border bg-white p-8"
          :class="p.highlight ? 'border-primary shadow-lg' : 'border-slate-100 shadow-card'"
        >
          <span v-if="p.tag" class="absolute -top-3 right-6 rounded-full bg-primary px-3 py-1 text-xs font-medium text-white">
            {{ p.tag }}
          </span>
          <h3 class="font-semibold text-slate-800">{{ p.name }}</h3>
          <p class="mt-1 text-xs text-slate-400">{{ p.desc }}</p>
          <div class="mt-4 flex items-baseline gap-1">
            <span class="text-4xl font-bold" :class="p.highlight ? 'text-primary' : 'text-slate-800'">{{ p.price }}</span>
            <span class="text-sm text-slate-400">{{ p.unit }}</span>
          </div>
          <ul class="mt-6 space-y-3">
            <li v-for="f in p.features" :key="f.text" class="flex items-center gap-2.5 text-sm text-slate-600">
              <svg viewBox="0 0 24 24" class="h-4 w-4 shrink-0" :class="p.highlight ? 'text-primary' : 'text-emerald-500'" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 13l4 4L19 7" />
              </svg>
              {{ f.text }}
            </li>
          </ul>
          <button class="mt-8 w-full py-3" :class="p.highlight ? 'btn-primary' : 'btn-ghost'">{{ p.action }}</button>
        </div>
      </div>
    </div>
  </section>
</template>
