import request, { errMsg } from './request'

/** 查询当前用户两个平台（抖音/Instagram）的 cookie 状态 */
export async function listCookies() {
  const { data } = await request.get('/cookies')
  if (!data.success) throw new Error(data.error || '查询失败')
  return data.data
}

/** 上传 cookies.txt（multipart/form-data，boundary 由 axios 自动设置） */
export async function uploadCookie(platform, file) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await request.post(`/cookies/${platform}`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
  if (!data.success) throw new Error(data.error || '上传失败')
  return data.data
}

export async function deleteCookie(platform) {
  const { data } = await request.delete(`/cookies/${platform}`)
  if (!data.success) throw new Error(data.error || '删除失败')
  return data.data
}

/** 后端 cookie/登录相关错误文案的统一识别（用于引导用户去维护） */
export function isCookieError(msg) {
  return typeof msg === 'string' && /cookies/i.test(msg)
}

export { errMsg }
