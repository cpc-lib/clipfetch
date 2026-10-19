<script setup>
import { defineAsyncComponent } from 'vue'
import AppHeader from './components/AppHeader.vue'
import AppFooter from './components/AppFooter.vue'

// 模态弹窗按需异步加载，未唤起时不进入首屏执行流
const AuthModal = defineAsyncComponent(() => import('./components/AuthModal.vue'))
const CookieModal = defineAsyncComponent(() => import('./components/CookieModal.vue'))
</script>

<template>
  <div class="min-h-screen">
    <AppHeader />

    <main>
      <RouterView />
    </main>

    <AppFooter />
    <AuthModal />
    <CookieModal />
  </div>
</template>
