import request, { errMsg } from './request'
import { setAuth, clearAuth, authState } from '../stores/auth'

export async function register(payload) {
  const { data } = await request.post('/auth/register', payload)
  setAuth(data.data)
  return data.data
}

export async function login(payload) {
  const { data } = await request.post('/auth/login', payload)
  setAuth(data.data)
  return data.data
}

export async function logout() {
  try {
    await request.post('/auth/logout', { refreshToken: authState.refreshToken })
  } catch {
    // 忽略登出接口异常，本地清理即可
  }
  clearAuth()
}

export { errMsg }
