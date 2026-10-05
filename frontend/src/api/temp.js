import request from './request'

/** 分页查询文件库列表 */
export async function fetchTempList({ page = 1, size = 20, downloaded, keyword } = {}) {
  const params = { page, size }
  if (downloaded !== undefined && downloaded !== '') params.downloaded = downloaded
  if (keyword) params.keyword = keyword
  const { data } = await request.get('/temp/list', { params })
  if (!data.success) throw new Error(data.error || '查询失败')
  return data.data
}

/** 标记已下载 */
export async function markDownloaded(url) {
  const { data } = await request.post('/temp/mark-downloaded', { url })
  if (!data.success) throw new Error(data.error || '标记失败')
}

/** 批量删除 */
export async function deleteTempLinks(ids) {
  const { data } = await request.post('/temp/delete', { ids })
  if (!data.success) throw new Error(data.error || '删除失败')
}
