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

/** 批量删除 */
export async function deleteTempLinks(ids) {
  const { data } = await request.post('/temp/delete', { ids })
  if (!data.success) throw new Error(data.error || '删除失败')
}
