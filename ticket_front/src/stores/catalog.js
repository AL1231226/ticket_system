import { ref } from 'vue'
import { defineStore } from 'pinia'

import { getCategories } from '@/api/show'

/**
 * 分类是低频数据，整个 App 只拉一次。
 * 首页分类栏、详情页的「演唱会/音乐节」标签都从这里取。
 */
export const useCatalogStore = defineStore('catalog', () => {
  const categories = ref([])
  const loaded = ref(false)
  const loading = ref(false)

  async function ensureCategories() {
    if (loaded.value || loading.value) {
      return categories.value
    }
    loading.value = true
    try {
      const res = await getCategories()
      categories.value = res.data || []
      loaded.value = true
    } finally {
      loading.value = false
    }
    return categories.value
  }

  function categoryName(id) {
    return categories.value.find((item) => item.id === id)?.name || ''
  }

  return { categories, loaded, loading, ensureCategories, categoryName }
})
