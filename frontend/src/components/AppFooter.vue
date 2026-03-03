<template>
  <footer class="bg-white border-t border-border-light py-8">
    <div class="max-w-6xl mx-auto px-4 sm:px-6">
      <div class="flex flex-col sm:flex-row items-center justify-between gap-4">
        <div class="flex items-center gap-2">
          <div class="w-7 h-7 rounded-lg bg-gradient-to-br from-primary to-blue-600 flex items-center justify-center">
            <svg class="w-4 h-4 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
          </div>
          <span class="text-sm font-medium text-text-primary">SaveAny</span>
        </div>
        <p class="text-xs text-text-muted text-center leading-relaxed">
          本工具仅供学习交流使用，请尊重视频版权，勿用于商业用途。下载内容的版权归原作者所有。
        </p>
        <p class="text-xs text-text-muted">
          &copy; {{ new Date().getFullYear() }} SaveAny
        </p>
      </div>
    </div>
  </footer>
</template>
