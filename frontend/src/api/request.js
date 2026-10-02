import axios from 'axios'
import { authState, setAuth, clearAuth } from '../stores/auth'

/**
 * axios 实例：自动携带 access token；401 时用 refresh token 刷新并重试一次
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 300000
})

request.interceptors.request.use((config) => {
  if (authState.accessToken) {
    config.headers.Authorization = `Bearer ${authState.accessToken}`
  }
  return config
})

let refreshing = null

request.interceptors.response.use(
  (resp) => resp,
  async (error) => {
    const { response, config } = error
    if (response && response.status === 401 && authState.refreshToken && !config._retried) {
      config._retried = true
      try {
        refreshing = refreshing || refreshTokens()
        const data = await refreshing
        refreshing = null
        if (data && data.accessToken) {
          setAuth(data)
          config.headers.Authorization = `Bearer ${data.accessToken}`
          return request(config)
        }
      } catch {
        refreshing = null
        clearAuth()
      }
    }
    return Promise.reject(error)
  }
)

async function refreshTokens() {
  const resp = await axios.post('/api/auth/refresh', {
    refreshToken: authState.refreshToken
  })
  return resp.data.data
}

/**
 * 统一提取后端错误信息
 */
export function errMsg(error) {
  const resp = error?.response?.data
  if (resp && resp.error) return resp.error
  if (error?.code === 'ECONNABORTED') return '请求超时，请稍后重试'
  if (error?.message) return error.message
  return '网络异常，请稍后重试'
}

export default request
