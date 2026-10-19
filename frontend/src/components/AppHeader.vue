<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isLoggedIn, authState, authModal } from '../stores/auth'
import { cookieModal } from '../stores/cookies'
import { logout } from '../api/auth'

const route = useRoute()
const router = useRouter()

// 用户菜单：悬停或点击用户名展开（桌面端）
const menuOpen = ref(false)
function toggleMenu() { menuOpen.value = !menuOpen.value }
function closeMenu() { menuOpen.value = false }
function openCookies() { closeMenu(); closeMobileMenu(); cookieModal.open() }
function doLogout() { closeMenu(); closeMobileMenu(); logout() }

// 移动端汉堡菜单折叠展开状态
const mobileMenuOpen = ref(false)
function toggleMobileMenu() { mobileMenuOpen.value = !mobileMenuOpen.value }
function closeMobileMenu() { mobileMenuOpen.value = false }

// 路由切换时自动关闭移动端菜单与用户菜单
watch(() => route.fullPath, () => {
  mobileMenuOpen.value = false
  menuOpen.value = false
})

function isRouteActive(target) {
  if (target === '/') return route.path === '/'
  return route.path.startsWith(target)
}

function goPlatforms() {
  closeMobileMenu()
  if (route.path !== '/') {
    router.push({ path: '/', hash: '#platforms' })
  } else {
    document.getElementById('platforms')?.scrollIntoView({ behavior: 'smooth' })
  }
}
</script>

<template>
  <header class="sticky top-0 z-40 w-full border-b border-slate-100 bg-white/95 backdrop-blur">
    <div class="mx-auto flex h-16 max-w-7xl items-center justify-between px-3 sm:px-6">
      <!-- 品牌 Logo -->
      <RouterLink to="/" class="flex items-center gap-2" @click="closeMobileMenu">
        <span class="flex h-8 w-8 items-center justify-center rounded-xl bg-primary text-white shrink-0">
          <svg viewBox="0 0 24 24" class="h-4.5 w-4.5" width="18" height="18" fill="currentColor">
            <path d="M8 5.14v14l11-7-11-7z" />
          </svg>
        </span>
        <span class="text-lg font-bold text-slate-800 tracking-tight">ClipFetch</span>
      </RouterLink>

      <!-- 桌面端横向导航栏（md 及以上屏幕显示） -->
      <nav class="hidden items-center gap-6 text-sm text-slate-600 md:flex">
        <RouterLink
          to="/"
          class="transition"
          :class="isRouteActive('/') ? 'font-medium text-primary' : 'hover:text-primary'"
        >链接解析</RouterLink>
        <RouterLink
          to="/subtitle"
          class="transition"
          :class="isRouteActive('/subtitle') ? 'font-medium text-primary' : 'hover:text-primary'"
        >字幕转换</RouterLink>
        <RouterLink
          to="/library"
          class="transition"
          :class="isRouteActive('/library') ? 'font-medium text-primary' : 'hover:text-primary'"
        >文件库</RouterLink>
        <RouterLink
          to="/preparse"
          class="transition"
          :class="isRouteActive('/preparse') ? 'font-medium text-primary' : 'hover:text-primary'"
        >预解析库</RouterLink>
        <RouterLink
          to="/magnet"
          class="transition"
          :class="isRouteActive('/magnet') ? 'font-medium text-primary' : 'hover:text-primary'"
        >磁力下载</RouterLink>
        <button class="transition hover:text-primary" @click="goPlatforms">支持平台</button>
      </nav>

      <!-- 右侧操作区：登录/注册/用户状态 + 移动端汉堡切换按钮 -->
      <div class="flex items-center gap-2 sm:gap-3">
        <template v-if="isLoggedIn">
          <div class="relative" @mouseleave="closeMenu">
            <button
              class="flex items-center gap-1.5 sm:gap-2 rounded-full border border-slate-200 px-2.5 sm:px-3 py-1.5 text-xs sm:text-sm transition hover:border-primary"
              :class="{ 'border-primary': menuOpen }"
              @click.stop="toggleMenu"
            >
              <span class="flex h-5 w-5 sm:h-6 sm:w-6 items-center justify-center rounded-full bg-primary-light text-[10px] sm:text-xs font-semibold text-primary">
                {{ (authState.user?.nickname || 'U').slice(0, 1).toUpperCase() }}
              </span>
              <span class="max-w-20 sm:max-w-28 truncate">{{ authState.user?.nickname || authState.user?.email }}</span>
              <span v-if="authState.user?.vip" class="rounded-full bg-amber-400/20 px-1 text-[10px] font-semibold text-amber-600">VIP</span>
            </button>
            <!-- 透明桥接区：填补按钮与菜单之间的空隙，避免 hover 移过时菜单消失 -->
            <div v-if="menuOpen" class="absolute right-0 top-full h-1 w-full" />
            <div
              v-if="menuOpen"
              class="absolute right-0 top-full z-50 mt-1 w-40 rounded-card border border-slate-100 bg-white p-1.5 shadow-card"
            >
              <div class="px-3 py-1.5 text-xs text-slate-400 truncate">{{ authState.user?.email }}</div>
              <button
                class="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-50 hover:text-primary"
                @click="openCookies"
              >
                <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M21 12a9 9 0 11-3.5-7.1M21 4v5h-5" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
                我的 Cookies
              </button>
              <button
                class="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-600 transition hover:bg-slate-50 hover:text-red-500"
                @click="doLogout"
              >退出登录</button>
            </div>
          </div>
        </template>
        <template v-else>
          <button class="btn-ghost text-xs sm:text-sm px-2.5 sm:px-3 py-1.5" @click="authModal.open('login')">登录</button>
          <button class="btn-primary text-xs sm:text-sm px-2.5 sm:px-4 py-1.5" @click="authModal.open('register')">免费注册</button>
        </template>

        <!-- 移动端汉堡菜单切换按钮 (屏幕小于 md 时显示) -->
        <button
          type="button"
          class="flex h-9 w-9 items-center justify-center rounded-lg border border-slate-200 text-slate-600 transition hover:border-primary hover:text-primary md:hidden"
          :class="{ 'border-primary text-primary bg-primary-light/20': mobileMenuOpen }"
          :aria-expanded="mobileMenuOpen"
          aria-label="切换移动端导航菜单"
          @click="toggleMobileMenu"
        >
          <!-- 汉堡三横图标 -->
          <svg v-if="!mobileMenuOpen" viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M4 6h16M4 12h16M4 18h16" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <!-- 关闭叉号图标 -->
          <svg v-else viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </div>

    <!-- 移动端抽屉/下拉式导航菜单 (md:hidden) -->
    <transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="opacity-0 -translate-y-2"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition duration-100 ease-in"
      leave-from-class="opacity-100 translate-y-0"
      leave-to-class="opacity-0 -translate-y-2"
    >
      <div
        v-if="mobileMenuOpen"
        class="border-t border-slate-100 bg-white/95 px-4 py-3 backdrop-blur md:hidden shadow-lg"
      >
        <nav class="flex flex-col gap-1 text-sm">
          <RouterLink
            to="/"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 transition"
            :class="isRouteActive('/') ? 'bg-primary/10 font-semibold text-primary' : 'text-slate-600 hover:bg-slate-50 hover:text-primary'"
            @click="closeMobileMenu"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>链接解析</span>
          </RouterLink>

          <RouterLink
            to="/subtitle"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 transition"
            :class="isRouteActive('/subtitle') ? 'bg-primary/10 font-semibold text-primary' : 'text-slate-600 hover:bg-slate-50 hover:text-primary'"
            @click="closeMobileMenu"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 6h16M4 12h10M4 18h14" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>字幕转换</span>
          </RouterLink>

          <RouterLink
            to="/library"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 transition"
            :class="isRouteActive('/library') ? 'bg-primary/10 font-semibold text-primary' : 'text-slate-600 hover:bg-slate-50 hover:text-primary'"
            @click="closeMobileMenu"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>文件库</span>
          </RouterLink>

          <RouterLink
            to="/preparse"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 transition"
            :class="isRouteActive('/preparse') ? 'bg-primary/10 font-semibold text-primary' : 'text-slate-600 hover:bg-slate-50 hover:text-primary'"
            @click="closeMobileMenu"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" stroke-linecap="round" stroke-linejoin="round"/>
              <polyline points="12 6 12 12 16 14" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>预解析库</span>
          </RouterLink>

          <RouterLink
            to="/magnet"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 transition"
            :class="isRouteActive('/magnet') ? 'bg-primary/10 font-semibold text-primary' : 'text-slate-600 hover:bg-slate-50 hover:text-primary'"
            @click="closeMobileMenu"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>磁力下载</span>
          </RouterLink>

          <button
            type="button"
            class="flex items-center gap-2 rounded-lg px-3 py-2.5 text-left text-slate-600 transition hover:bg-slate-50 hover:text-primary"
            @click="goPlatforms"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" stroke-linecap="round" stroke-linejoin="round"/>
              <line x1="2" y1="12" x2="22" y2="12" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            <span>支持平台</span>
          </button>

          <!-- 登录状态下的快捷操作 -->
          <div v-if="isLoggedIn" class="mt-2 border-t border-slate-100 pt-2 flex flex-col gap-1">
            <button
              type="button"
              class="flex items-center gap-2 rounded-lg px-3 py-2 text-left text-slate-600 transition hover:bg-slate-50 hover:text-primary"
              @click="openCookies"
            >
              <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 12a9 9 0 11-3.5-7.1M21 4v5h-5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span>我的 Cookies</span>
            </button>
            <button
              type="button"
              class="flex items-center gap-2 rounded-lg px-3 py-2 text-left text-slate-600 transition hover:bg-slate-50 hover:text-red-500"
              @click="doLogout"
            >
              <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <span>退出登录</span>
            </button>
          </div>
        </nav>
      </div>
    </transition>
  </header>
</template>
