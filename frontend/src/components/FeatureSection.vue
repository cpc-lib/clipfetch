<script setup>
const features = [
  {
    title: '多平台解析下载',
    desc: '基于 yt-dlp 支持 1800+ 网站，YouTube、抖音、Twitter 等主流平台一键解析，多种清晰度自由选择。',
    icon: 'M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z'
  },
  {
    title: '抖音无水印',
    desc: '专用抖音解析模块，无需登录无需 Cookie，粘贴链接直接获取无水印原画视频。',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z'
  },
  {
    title: 'AI 视频总结',
    desc: '自动提取字幕，通义千问大模型生成总结摘要、内容大纲与核心要点，长视频快速看懂。',
    icon: 'M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z'
  },
  {
    title: '思维导图',
    desc: 'AI 自动生成视频内容思维导图，支持全屏浏览与高清 PNG / SVG 导出，整理笔记更轻松。',
    icon: 'M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z'
  },
  {
    title: 'AI 问答助手',
    desc: '针对视频内容自由提问，AI 基于字幕上下文精准回答，多轮对话辅助深度学习。',
    icon: 'M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z'
  }
]
</script>

<template>
  <section id="features" class="mx-auto max-w-7xl px-4 py-16 sm:px-6">
    <h2 class="text-center text-2xl font-bold text-slate-900 sm:text-3xl">功能亮点</h2>
    <p class="mt-2 text-center text-sm text-slate-400">不只是下载，更是你的视频学习效率工具</p>
    <div class="mt-10 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-5">
      <div
        v-for="f in features"
        :key="f.title"
        class="group rounded-card border border-slate-100 bg-white p-6 shadow-card transition hover:-translate-y-1 hover:border-primary/30"
      >
        <span class="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-light text-primary transition group-hover:bg-primary group-hover:text-white">
          <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <path :d="f.icon" />
          </svg>
        </span>
        <h3 class="mt-4 font-semibold text-slate-800">{{ f.title }}</h3>
        <p class="mt-2 text-sm leading-6 text-slate-400">{{ f.desc }}</p>
      </div>
    </div>
  </section>
</template>
