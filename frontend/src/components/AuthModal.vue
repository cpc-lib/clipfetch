<script setup>
import { ref, watch, nextTick } from 'vue'
import { login, register, errMsg } from '../api/auth'
import { authModal } from '../stores/auth'

const email = ref('')
const password = ref('')
const nickname = ref('')
const loading = ref(false)
const errorText = ref('')
const emailInput = ref(null)

watch(() => authModal.visible, async (v) => {
  if (v) {
    errorText.value = ''
    await nextTick()
    emailInput.value?.focus()
  }
})

async function submit() {
  if (loading.value) return
  errorText.value = ''
  if (!email.value.trim() || !password.value) {
    errorText.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    if (authModal.mode === 'register') {
      await register({ email: email.value.trim(), password: password.value, nickname: nickname.value.trim() })
    } else {
      await login({ email: email.value.trim(), password: password.value })
    }
    authModal.close()
    email.value = ''
    password.value = ''
    nickname.value = ''
  } catch (e) {
    errorText.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="authModal.visible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-sm"
      @click.self="authModal.close()"
    >
      <div class="w-full max-w-sm rounded-card bg-white p-6 shadow-xl sm:p-8">
        <h3 class="text-xl font-bold text-slate-800">
          {{ authModal.mode === 'register' ? '创建账号' : '欢迎回来' }}
        </h3>
        <p class="mt-1 text-sm text-slate-400">
          {{ authModal.mode === 'register' ? '注册即可免费使用 AI 视频总结' : '登录你的账号继续使用' }}
        </p>

        <form class="mt-6 space-y-4" @submit.prevent="submit">
          <div>
            <label class="mb-1.5 block text-xs font-medium text-slate-500">邮箱</label>
            <input
              ref="emailInput"
              v-model="email"
              type="email"
              autocomplete="email"
              class="w-full rounded-xl border border-slate-200 px-4 py-2.5 text-sm outline-none transition focus:border-primary"
              placeholder="you@example.com"
            />
          </div>
          <div v-if="authModal.mode === 'register'">
            <label class="mb-1.5 block text-xs font-medium text-slate-500">昵称（可选）</label>
            <input
              v-model="nickname"
              type="text"
              class="w-full rounded-xl border border-slate-200 px-4 py-2.5 text-sm outline-none transition focus:border-primary"
              placeholder="怎么称呼你？"
            />
          </div>
          <div>
            <label class="mb-1.5 block text-xs font-medium text-slate-500">密码</label>
            <input
              v-model="password"
              type="password"
              :autocomplete="authModal.mode === 'register' ? 'new-password' : 'current-password'"
              class="w-full rounded-xl border border-slate-200 px-4 py-2.5 text-sm outline-none transition focus:border-primary"
              placeholder="请输入密码"
            />
          </div>

          <p v-if="errorText" class="text-xs text-red-500">{{ errorText }}</p>

          <button type="submit" class="btn-primary w-full py-3" :disabled="loading">
            {{ loading ? '处理中…' : authModal.mode === 'register' ? '注册' : '登录' }}
          </button>
        </form>

        <p class="mt-5 text-center text-sm text-slate-400">
          <template v-if="authModal.mode === 'register'">
            已有账号？
            <button class="text-primary hover:underline" @click="authModal.mode = 'login'">直接登录</button>
          </template>
          <template v-else>
            还没有账号？
            <button class="text-primary hover:underline" @click="authModal.mode = 'register'">免费注册</button>
          </template>
        </p>
      </div>
    </div>
  </Teleport>
</template>
