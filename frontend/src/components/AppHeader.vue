<script setup>
import { isLoggedIn, authState, authModal } from '../stores/auth'
import { cookieModal } from '../stores/cookies'
import { logout } from '../api/auth'
</script>

<template>
  <header class="sticky top-0 z-40 w-full border-b border-slate-100 bg-white/90 backdrop-blur">
    <div class="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6">
      <a href="#" class="flex items-center gap-2">
        <span class="flex h-8 w-8 items-center justify-center rounded-xl bg-primary text-white">
          <svg viewBox="0 0 24 24" class="h-4.5 w-4.5" width="18" height="18" fill="currentColor">
            <path d="M8 5.14v14l11-7-11-7z" />
          </svg>
        </span>
        <span class="text-lg font-bold text-slate-800">ClipFetch</span>
      </a>

      <nav class="hidden items-center gap-6 text-sm text-slate-600 md:flex">
        <a href="#features" class="transition hover:text-primary">功能亮点</a>
        <a href="#platforms" class="transition hover:text-primary">支持平台</a>
      </nav>

      <div class="flex items-center gap-3">
        <template v-if="isLoggedIn">
          <div class="group relative">
            <button class="flex items-center gap-2 rounded-full border border-slate-200 px-3 py-1.5 text-sm transition hover:border-primary">
              <span class="flex h-6 w-6 items-center justify-center rounded-full bg-primary-light text-xs font-semibold text-primary">
                {{ (authState.user?.nickname || 'U').slice(0, 1).toUpperCase() }}
              </span>
              <span class="max-w-24 truncate">{{ authState.user?.nickname || authState.user?.email }}</span>
              <span v-if="authState.user?.vip" class="rounded-full bg-amber-400/20 px-1.5 text-[10px] font-semibold text-amber-600">VIP</span>
            </button>
            <div class="invisible absolute right-0 top-full z-50 mt-1 w-40 rounded-card border border-slate-100 bg-white p-1.5 opacity-0 shadow-card transition group-hover:visible group-hover:opacity-100">
              <div class="px-3 py-1.5 text-xs text-slate-400">{{ authState.user?.email }}</div>
              <button
                class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-50 hover:text-primary"
                @click="cookieModal.open()"
              >
                <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 12a9 9 0 11-3.5-7.1M21 4v5h-5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                我的 Cookies
              </button>
              <button
                class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-50 hover:text-red-500"
                @click="logout()"
              >退出登录</button>
            </div>
          </div>
        </template>
        <template v-else>
          <button class="btn-ghost" @click="authModal.open('login')">登录</button>
          <button class="btn-primary" @click="authModal.open('register')">免费注册</button>
        </template>
      </div>
    </div>
  </header>
</template>
