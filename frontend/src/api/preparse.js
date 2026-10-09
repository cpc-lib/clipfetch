import request from './request'

/** 分页查询预解析库列表 */
export async function fetchPreParseList({ page = 1, size = 20, parsed, url, keyword } = {}) {
  const params = { page, size }
  if (parsed !== undefined && parsed !== '') params.parsed = parsed
  if (url) params.url = url
  if (keyword) params.keyword = keyword
  const { data } = await request.get('/pre-parse/list', { params })
  if (!data.success) throw new Error(data.error || '查询失败')
  return data.data
}

/** 新增预解析记录 */
export async function addPreParse({ url, title } = {}) {
  const { data } = await request.post('/pre-parse/add', { url, title })
  if (!data.success) throw new Error(data.error || '添加失败')
  return data.data
}

/** 批量删除 */
export async function deletePreParse(ids) {
  const { data } = await request.post('/pre-parse/delete', { ids })
  if (!data.success) throw new Error(data.error || '删除失败')
}

/** 标记已解析 */
export async function markParsed(url) {
  const { data } = await request.post('/pre-parse/mark-parsed', { url })
  if (!data.success) throw new Error(data.error || '标记失败')
}

/** 编辑文件名称 */
export async function renamePreParse(id, title) {
  const { data } = await request.post('/pre-parse/rename', { id, title })
  if (!data.success) throw new Error(data.error || '保存失败')
}
