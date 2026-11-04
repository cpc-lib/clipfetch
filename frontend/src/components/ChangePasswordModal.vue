<script setup>
import { ref, watch, nextTick } from 'vue'
import { changePassword, errMsg } from '../api/auth'
import { passwordModal } from '../stores/auth'

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const errorText = ref('')
const successText = ref('')
const showOld = ref(false)
const showNew = ref(false)
const showConfirm = ref(false)
const oldInput = ref(null)

watch(() => passwordModal.visible, async (v) => {
  if (v) {
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    errorText.value = ''
    successText.value = ''
    await nextTick()
    oldInput.value?.focus()
  }
})

async function submit() {
  if (loading.value) return
  errorText.value = ''
  successText.value = ''
  if (!oldPassword.value || !newPassword.value || !confirmPassword.value) {
    errorText.value = '请填写所有密码字段'
    return
  }
  if (newPassword.value.length < 6) {
    errorText.value = '新密码至少 6 位'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    errorText.value = '两次输入的新密码不一致'
    return
  }
  if (newPassword.value === oldPassword.value) {
    errorText.value = '新密码不能与原密码相同'
    return
  }
  loading.value = true
  try {
    await changePassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    successText.value = '密码修改成功'
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    setTimeout(() => passwordModal.close(), 800)
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
      v-if="passwordModal.visible"
      class="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4 backdrop-blur-sm"
      @click.self="passwordModal.close()"
    >
      <div class="w-full max-w-sm rounded-card bg-white p-6 shadow-xl sm:p-8">
        <h3 class="text-xl font-bold text-slate-800">修改密码</h3>
        <p class="mt-1 text-sm text-slate-400">请输入原密码并设置新密码</p>

        <form class="mt-6 space-y-4" @submit.prevent="submit">
          <div>
            <label class="mb-1.5 block text-xs font-medium text-slate-500">原密码</label>
            <div class="relative">
              <input
                ref="oldInput"
                v-model="oldPassword"
                :type="showOld ? 'text' : 'password'"
                autocomplete="current-password"
                class="w-full rounded-xl border border-slate-200 px-4 py-2.5 pr-11 text-sm outline-none transition focus:border-primary"
                placeholder="请输入原密码"
              />
              <button
                type="button"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 transition hover:text-primary"
                :aria-label="showOld ? '隐藏密码' : '显示密码'"
                @click="showOld = !showOld"
              >
                <svg v-if="showOld" viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" stroke-linecap="round" stroke-linejoin="round" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M9.9 4.24A9.12 9.12 0 0112 4c6.5 0 10 8 10 8a18.5 18.5 0 01-1.67 2.68M6.61 6.61A13.53 13.53 0 002 12s3.5 8 10 8a9.74 9.74 0 005.39-1.61" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M14.12 14.12a3 3 0 11-4.24-4.24" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M2 2l20 20" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </div>

          <div>
            <label class="mb-1.5 block text-xs font-medium text-slate-500">新密码</label>
            <div class="relative">
              <input
                v-model="newPassword"
                :type="showNew ? 'text' : 'password'"
                autocomplete="new-password"
                class="w-full rounded-xl border border-slate-200 px-4 py-2.5 pr-11 text-sm outline-none transition focus:border-primary"
                placeholder="至少 6 位"
              />
              <button
                type="button"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 transition hover:text-primary"
                :aria-label="showNew ? '隐藏密码' : '显示密码'"
                @click="showNew = !showNew"
              >
                <svg v-if="showNew" viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" stroke-linecap="round" stroke-linejoin="round" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M9.9 4.24A9.12 9.12 0 0112 4c6.5 0 10 8 10 8a18.5 18.5 0 01-1.67 2.68M6.61 6.61A13.53 13.53 0 002 12s3.5 8 10 8a9.74 9.74 0 005.39-1.61" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M14.12 14.12a3 3 0 11-4.24-4.24" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M2 2l20 20" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </div>

          <div>
            <label class="mb-1.5 block text-xs font-medium text-slate-500">确认新密码</label>
            <div class="relative">
              <input
                v-model="confirmPassword"
                :type="showConfirm ? 'text' : 'password'"
                autocomplete="new-password"
                class="w-full rounded-xl border border-slate-200 px-4 py-2.5 pr-11 text-sm outline-none transition focus:border-primary"
                placeholder="再次输入新密码"
              />
              <button
                type="button"
                class="absolute right-3 top-1/2 -translate-y-1/2 text-slate-400 transition hover:text-primary"
                :aria-label="showConfirm ? '隐藏密码' : '显示密码'"
                @click="showConfirm = !showConfirm"
              >
                <svg v-if="showConfirm" viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z" stroke-linecap="round" stroke-linejoin="round" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
                <svg v-else viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                  <path d="M9.9 4.24A9.12 9.12 0 0112 4c6.5 0 10 8 10 8a18.5 18.5 0 01-1.67 2.68M6.61 6.61A13.53 13.53 0 002 12s3.5 8 10 8a9.74 9.74 0 005.39-1.61" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M14.12 14.12a3 3 0 11-4.24-4.24" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M2 2l20 20" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </div>

          <p v-if="errorText" class="text-xs text-red-500">{{ errorText }}</p>
          <p v-if="successText" class="text-xs text-green-600">{{ successText }}</p>

          <button type="submit" class="btn-primary w-full py-3" :disabled="loading">
            {{ loading ? '处理中…' : '确认修改' }}
          </button>
        </form>
      </div>
    </div>
  </Teleport>
</template>
