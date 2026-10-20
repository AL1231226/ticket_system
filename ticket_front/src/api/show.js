import request from '@/utils/request'

/** 分类列表。后端带 total，这里保留完整结果给 catalog store 使用。 */
export function getCategories() {
  return request.get('/show/category/list', { withTotal: true })
}

/** 分类下的演出列表；city 为空时不传，由后端返回全部城市。 */
export function getShowList({ categoryId, city, page = 1 }) {
  return request.get('/show/list/byCategory', {
    params: { categoryId, city: city || undefined, page },
    withTotal: true,
  })
}

/** 演出详情：show + sessions（每个 session 带 tiers）。 */
export function getShowDetail(id) {
  return request.get(`/show/${id}`)
}

/** 票档实时余量，详情页每 5 秒轮询一次。 */
export function getTierStock(tierId) {
  return request.get(`/ticket-tier/${tierId}`)
}
