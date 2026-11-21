import request from '@/utils/request'

/**
 * 社区流。
 * mode=follow 关注流（需要登录，未登录/无关注时后端回落全站并标记 fallbackGlobal）；
 * mode=latest 全站最新。
 * lastId + offset 是 Feed 游标：offset 必须原样回传，否则同毫秒边界会重复。
 */
export function getCommunityFeed({ mode = 'latest', lastId, offset = 0, count = 10 } = {}) {
  return request.get('/post/scroll', {
    params: {
      mode,
      lastId: lastId ?? undefined,
      offset,
      count,
    },
  })
}

/** 动态详情：含作者、关联演出卡片、likedByMe。 */
export function getPostDetail(id) {
  return request.get(`/post/${id}`)
}

/** 发布动态，返回新动态 id。正文和图片至少填一项。 */
export function publishPost(payload) {
  return request.post('/post', payload)
}

/** 删除自己的动态（软删）。 */
export function deletePost(id) {
  return request.delete(`/post/${id}`)
}

/** 点赞切换，返回 { liked, likedCount }。 */
export function togglePostLike(id) {
  return request.put(`/post/${id}/like`)
}

/** 最近点赞用户 Top5。 */
export function getPostLikes(id, limit = 5) {
  return request.get(`/post/${id}/likes`, { params: { limit } })
}

/** 某用户的动态，游标分页。 */
export function getUserPosts(userId, { lastId, offset = 0, count = 10 } = {}) {
  return request.get(`/post/of/user/${userId}`, {
    params: {
      lastId: lastId ?? undefined,
      offset,
      count,
    },
  })
}

/** 某演出的动态（观演 repo），游标分页。 */
export function getShowPosts(showId, { lastId, offset = 0, count = 3 } = {}) {
  return request.get(`/post/of/show/${showId}`, {
    params: {
      lastId: lastId ?? undefined,
      offset,
      count,
    },
  })
}

/** 关注 / 粉丝列表，type=followees|fans。 */
export function getFollowList(userId, { type = 'followees', lastId, count = 10 } = {}) {
  return request.get(`/follow/list/${userId}`, {
    params: {
      type,
      lastId: lastId ?? undefined,
      count,
    },
  })
}

/** 我和目标用户的共同关注（需登录）。 */
export function getCommonFollows(userId) {
  return request.get(`/follow/common/${userId}`)
}

/** 动态评论列表，按 (createTime, id) 倒序游标分页。 */
export function getPostComments(postId, { lastId, offset = 0, count = 10 } = {}) {
  return request.get(`/comment/of/post/${postId}`, {
    params: {
      lastId: lastId ?? undefined,
      offset,
      count,
    },
  })
}

/** 发一级评论。 */
export function createComment({ postId, content }) {
  return request.post('/comment', { postId, content })
}

/** 删除自己的评论（软删）。 */
export function deleteComment(id) {
  return request.delete(`/comment/${id}`)
}

/** 关注 / 取关，返回 { following, fans }。 */
export function toggleFollow(userId, isFollow) {
  return request.post(`/follow/${userId}/${isFollow}`)
}

/** 上传动态图片，返回 { name, url }。 */
export function uploadCommunityImage(file) {
  const form = new FormData()
  form.append('file', file)
  return request.post('/upload/blog', form)
}
