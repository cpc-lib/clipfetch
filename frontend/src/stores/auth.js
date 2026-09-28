import { reactive, computed } from 'vue'

const STORAGE_KEY = 'fvd_auth'

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)) || {}
  } catch {
    return {}
  }
}

export const authState = reactive({
  user: null,
  accessToken: '',
  refreshToken: '',
  ...load()
})

export const isLoggedIn = computed(() => !!authState.accessToken)

export function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    user: authState.user,
    accessToken: authState.accessToken,
    refreshToken: authState.refreshToken
  }))
}

export function setAuth({ accessToken, refreshToken, user }) {
  authState.accessToken = accessToken || ''
  authState.refreshToken = refreshToken || ''
  authState.user = user || null
  persist()
}

export function clearAuth() {
  authState.accessToken = ''
  authState.refreshToken = ''
  authState.user = null
  localStorage.removeItem(STORAGE_KEY)
}

/** 弹窗状态由全局管理，Header/Hero 等组件共用 */
export const authModal = reactive({
  visible: false,
  mode: 'login', // login | register
  open(mode = 'login') {
    this.mode = mode
    this.visible = true
  },
  close() {
    this.visible = false
  }
})
