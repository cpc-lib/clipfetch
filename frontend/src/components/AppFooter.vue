<script setup>
const year = new Date().getFullYear()
</script>

<template>
  <footer class="border-t border-pink-100 bg-[#FFF5FA]">
    <div class="mx-auto max-w-7xl px-4 py-10 sm:px-6">
      <div class="flex flex-col items-center gap-3 text-center">
        <div class="flex items-center gap-2">
          <span class="flex h-7 w-7 items-center justify-center rounded-lg bg-primary text-white">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
              <path d="M8 5.14v14l11-7-11-7z" />
            </svg>
          </span>
          <span class="font-bold text-slate-700">ClipFetch</span>
        </div>
        <p class="max-w-2xl text-xs leading-5 text-slate-400">
          本工具仅供个人学习与研究使用，请勿用于商业用途。请仅下载自己拥有版权或已获得合法授权的内容，
          并遵守所在地区法律法规及各平台服务条款。视频版权归原作者所有，本站不存储任何视频文件。
        </p>
        <p class="text-xs text-slate-300">© {{ year }} Free Video Downloader · Powered by yt-dlp</p>
      </div>
    </div>
  </footer>
</template>
