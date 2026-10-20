<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { getShowDetail, getShowList } from '@/api/show'
import AppIcon from '@/components/AppIcon.vue'
import Countdown from '@/components/Countdown.vue'
import ShowCard from '@/components/ShowCard.vue'
import StateView from '@/components/StateView.vue'
import { useCatalogStore } from '@/stores/catalog'
import { formatDate } from '@/utils/format'

const CITIES = ['上海', '北京', '广州', '成都']

const router = useRouter()
const catalogStore = useCatalogStore()

const categories = computed(() => catalogStore.categories)
const cityOptions = [{ label: '全部城市', value: '' }, ...CITIES.map((c) => ({ label: c, value: c }))]

const activeCategoryId = ref(null)
const city = ref('')
const cityOpen = ref(false)
const shows = ref([])
const total = ref(0)
const page = ref(1)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const featured = ref(null)
const featuredLoading = ref(false)
const heroFailed = ref(false)

let requestSeq = 0
let featuredSeq = 0

const hasMore = computed(() => shows.value.length < total.value)
const heroFallback = computed(() => {
  const palettes = [
    ['#ff3d71', '#4b1b63'],
    ['#00d2ff', '#1b2a6b'],
    ['#ffcb47', '#7a3b12'],
    ['#2ed573', '#0b3d2c'],
    ['#e056fd', '#2b1550'],
  ]
  const id = Number(featured.value?.show?.id || 0)
  const p = palettes[id % palettes.length]
  return { background: `linear-gradient(145deg, ${p[0]}, ${p[1]})` }
})

onMounted(async () => {
  try {
    const list = await catalogStore.ensureCategories()
    if (list.length) {
      activeCategoryId.value = list[0].id
    } else {
      error.value = '还没有演出分类'
      loading.value = false
    }
  } catch (e) {
    error.value = e.message
    loading.value = false
  }
})

watch(activeCategoryId, (id) => {
  if (id) fetchShows(true)
})

watch(city, () => {
  if (activeCategoryId.value) fetchShows(true)
})

async function fetchShows(reset = false) {
  if (!activeCategoryId.value) return
  const seq = ++requestSeq
  if (reset) {
    page.value = 1
    loading.value = true
    error.value = ''
    shows.value = []
  } else {
    loadingMore.value = true
  }

  try {
    const res = await getShowList({
      categoryId: activeCategoryId.value,
      city: city.value,
      page: page.value,
    })
    if (seq !== requestSeq) return

    const list = res.data || []
    total.value = Number(res.total || 0)
    shows.value = reset ? list : [...shows.value, ...list]

    if (reset && list.length) {
      loadFeatured(list[0])
    } else if (reset) {
      featured.value = null
    }
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

async function loadFeatured(show) {
  const seq = ++featuredSeq
  featuredLoading.value = true
  heroFailed.value = false
  try {
    const detail = await getShowDetail(show.id)
    if (seq !== featuredSeq) return

    const sessions = detail.sessions || []
    const now = Date.now()
    const upcoming = sessions
      .filter((s) => s.status === 0 && s.onSaleTime && new Date(s.onSaleTime).getTime() > now)
      .sort((a, b) => new Date(a.onSaleTime) - new Date(b.onSaleTime))[0]
    const onSale = sessions
      .filter((s) => s.status === 1 || s.status === 2)
      .sort((a, b) => new Date(a.sessionTime) - new Date(b.sessionTime))[0]
    const session = upcoming || onSale

    if (!session) {
      featured.value = { show, session: null, mode: 'static', minStock: null }
      return
    }

    const stocks = (session.tiers || [])
      .map((tier) => Number(tier.stock))
      .filter((n) => Number.isFinite(n))

    featured.value = {
      show,
      session,
      mode: upcoming ? 'countdown' : 'hot',
      minStock: stocks.length ? Math.min(...stocks) : null,
    }
  } catch {
    if (seq === featuredSeq) {
      featured.value = { show, session: null, mode: 'static', minStock: null }
    }
  } finally {
    if (seq === featuredSeq) featuredLoading.value = false
  }
}

function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  page.value += 1
  fetchShows(false)
}

function openShow(id) {
  router.push(`/show/${id}`)
}

function selectCity(value) {
  city.value = value
  cityOpen.value = false
}

function pickAnotherCategory() {
  city.value = ''
  const index = categories.value.findIndex((item) => item.id === activeCategoryId.value)
  const next = categories.value[(index + 1) % categories.value.length]
  if (next) {
    activeCategoryId.value = next.id
  }
}
</script>

<template>
  <div class="home page">
    <header class="home__top">
      <div class="home__brand">
        <span class="home__mark">声</span>
        <div>
          <h1>声浪</h1>
          <p>把今晚留给现场</p>
        </div>
      </div>

      <button class="home__city" type="button" @click="cityOpen = true">
        <AppIcon name="location" :size="14" />
        <span>{{ city || '全部城市' }}</span>
        <i class="home__caret" />
      </button>
    </header>

    <div v-if="categories.length" class="home__cats">
      <button
        v-for="(category, index) in categories"
        :key="category.id"
        class="cat"
        :class="{ 'is-active': activeCategoryId === category.id }"
        type="button"
        @click="activeCategoryId = category.id"
      >
        <span class="cat__icon" :class="`cat__icon--${index % 5}`">
          {{ category.name.slice(0, 1) }}
        </span>
        <span class="cat__label">{{ category.name }}</span>
      </button>
    </div>

    <section
      v-if="featured"
      class="hero"
      :class="{ 'hero--static': featured.mode === 'static' }"
      @click="openShow(featured.show.id)"
    >
      <div class="hero__media" :style="heroFallback">
        <img
          v-if="featured.show.poster && !heroFailed"
          :src="featured.show.poster"
          :alt="featured.show.name"
          @error="heroFailed = true"
        />
        <span v-if="heroFailed || !featured.show.poster" class="hero__initial">
          {{ (featured.show.name || '演').slice(0, 1) }}
        </span>
      </div>
      <div class="hero__shade" />

      <div class="hero__content">
        <span class="hero__eyebrow">本周焦点</span>
        <h2>{{ featured.show.name }}</h2>
        <p>{{ featured.show.artist || '现场演出' }} · {{ featured.show.city }} · {{ formatDate(featured.show.startDate) }}</p>

        <div class="hero__cta">
          <Countdown
            v-if="featured.mode === 'countdown'"
            :target="featured.session.onSaleTime"
            label="距开票"
            compact
          />
          <span v-else-if="featured.mode === 'hot'" class="hero__hot">
            <i />
            正在热抢
            <em v-if="featured.minStock !== null">最低 {{ featured.minStock }} 张</em>
          </span>
          <span v-else class="hero__hot">查看详情</span>
        </div>
      </div>

      <span class="hero__arrow">
        <AppIcon name="chevron-right" :size="18" />
      </span>
    </section>

    <section v-else-if="featuredLoading" class="hero hero--skeleton" />

    <div class="section-title">
      <h2>近期演出</h2>
      <span v-if="total">共 {{ total }} 场</span>
    </div>

    <div v-if="loading" class="home__skeletons">
      <div v-for="i in 3" :key="i" class="skeleton-card">
        <div class="skeleton-card__poster" />
        <div class="skeleton-card__lines">
          <i /><i /><i />
        </div>
      </div>
    </div>

    <StateView
      v-else-if="error"
      title="加载失败"
      :description="error"
      action-text="重试"
      @action="fetchShows(true)"
    />

    <StateView
      v-else-if="!shows.length"
      title="这个分类还没有演出"
      description="换个分类或城市看看，新的演出会在这里出现。"
      action-text="看看其他分类"
      @action="pickAnotherCategory"
    />

    <div v-else class="home__list">
      <ShowCard v-for="show in shows" :key="show.id" :show="show" @click="openShow(show.id)" />

      <button v-if="hasMore" class="home__more" type="button" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '加载中…' : '加载更多' }}
      </button>
      <p v-else class="home__end">已经到底了</p>
    </div>

    <Teleport to="body">
      <div v-if="cityOpen" class="city-mask" @click.self="cityOpen = false">
        <div class="city-sheet">
          <div class="city-sheet__head">
            <h3>选择城市</h3>
            <button type="button" aria-label="关闭" @click="cityOpen = false">
              <AppIcon name="x" :size="18" />
            </button>
          </div>

          <div class="city-sheet__grid">
            <button
              v-for="item in cityOptions"
              :key="item.value"
              type="button"
              :class="{ 'is-active': city === item.value }"
              @click="selectCity(item.value)"
            >
              {{ item.label }}
            </button>
          </div>

          <p class="city-sheet__hint">城市列表来自当前演示数据，后续接城市接口后替换。</p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped lang="scss">
.home {
  padding-bottom: 18px;
}

.home__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: calc(16px + env(safe-area-inset-top)) 16px 8px;
  background: linear-gradient(180deg, #1a1020, var(--bg-base));
}

.home__brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.home__mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: linear-gradient(135deg, var(--brand), var(--brand-alt));
  box-shadow: 0 8px 22px rgba(255, 61, 113, 0.3);
  color: #fff;
  font-size: 17px;
  font-weight: 800;
}

.home__brand h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 800;
  letter-spacing: 1px;
}

.home__brand p {
  margin: 3px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
  letter-spacing: 0.3px;
}

.home__city {
  display: flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border: 1px solid var(--line);
  border-radius: 999px;
  background: rgba(26, 26, 34, 0.8);
  color: var(--text-2);
  font-size: 11px;
  cursor: pointer;
}

.home__caret {
  width: 0;
  height: 0;
  border-top: 4px solid var(--text-3);
  border-right: 3px solid transparent;
  border-left: 3px solid transparent;
}

.home__cats {
  display: flex;
  gap: 14px;
  overflow-x: auto;
  padding: 10px 16px 4px;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.cat {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 6px;
  flex: 0 0 auto;
  padding: 0;
  border: 0;
  background: none;
  color: var(--text-2);
  font-size: 10px;
  cursor: pointer;

  &.is-active {
    color: var(--text-1);

    .cat__icon {
      box-shadow: 0 0 0 1.5px var(--brand), 0 8px 18px rgba(255, 61, 113, 0.2);
    }
  }
}

.cat__icon {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border-radius: 13px;
  color: #fff;
  font-size: 16px;
  font-weight: 800;
  transition: box-shadow 0.18s ease;
}

.cat__icon--0 {
  background: linear-gradient(135deg, #ff3d71, #a9224b);
}

.cat__icon--1 {
  background: linear-gradient(135deg, #7b61ff, #4432b8);
}

.cat__icon--2 {
  background: linear-gradient(135deg, #ffcb47, #b97900);
  color: #211306;
}

.cat__icon--3 {
  background: linear-gradient(135deg, #2ed573, #117a4e);
}

.cat__icon--4 {
  background: linear-gradient(135deg, #00d2ff, #0b679e);
  color: #061a24;
}

.cat__label {
  white-space: nowrap;
}

.hero {
  position: relative;
  height: 174px;
  margin: 14px 16px 0;
  overflow: hidden;
  border-radius: 18px;
  background: var(--bg-card);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.42);
  cursor: pointer;
  animation: rise 0.35s ease both;
}

.hero__media {
  position: absolute;
  inset: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: center 32%;
  }
}

.hero__initial {
  position: absolute;
  right: 18px;
  bottom: 34px;
  color: rgba(255, 255, 255, 0.15);
  font-size: 92px;
  font-weight: 800;
  letter-spacing: -8px;
}

.hero__shade {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(8, 8, 12, 0.82) 0%, rgba(8, 8, 12, 0.28) 64%, rgba(8, 8, 12, 0.1)),
    linear-gradient(0deg, rgba(8, 8, 12, 0.82), transparent 52%);
}

.hero__content {
  position: absolute;
  right: 18px;
  bottom: 16px;
  left: 18px;
  z-index: 2;

  h2 {
    display: -webkit-box;
    overflow: hidden;
    max-width: 300px;
    margin: 5px 0 0;
    color: #fff;
    font-size: 18px;
    font-weight: 800;
    letter-spacing: -0.4px;
    line-height: 1.28;
    // 长演出名在两行之间更均匀，避免最后一个字单独掉到下一行
    text-wrap: balance;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }

  p {
    overflow: hidden;
    margin: 6px 0 0;
    color: rgba(255, 255, 255, 0.62);
    font-size: 10px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.hero__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 5px;
  background: rgba(255, 61, 113, 0.18);
  color: #ff8baa;
  font-size: 8.5px;
  font-weight: 700;
  letter-spacing: 0.6px;
}

.hero__cta {
  margin-top: 10px;
}

.hero__hot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--brand);
  font-size: 11px;
  font-weight: 700;

  i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--brand);
    box-shadow: 0 0 0 4px rgba(255, 61, 113, 0.12);
    animation: pulse 1.6s ease-in-out infinite;
  }

  em {
    color: rgba(255, 255, 255, 0.5);
    font-size: 9.5px;
    font-style: normal;
    font-weight: 500;
  }
}

.hero__arrow {
  position: absolute;
  right: 14px;
  bottom: 16px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 50%;
  background: rgba(7, 7, 10, 0.36);
  color: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(8px);
}

.hero--static {
  height: 148px;
}

.hero--skeleton {
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
  box-shadow: none;
}

.home__list {
  padding-bottom: 4px;
}

.home__more {
  display: block;
  width: calc(100% - 32px);
  margin: 14px 16px 4px;
  padding: 11px 0;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--bg-card);
  color: var(--text-2);
  font-size: 11.5px;
  cursor: pointer;

  &:disabled {
    opacity: 0.55;
  }
}

.home__end {
  margin: 16px 0 4px;
  color: var(--text-3);
  font-size: 10px;
  text-align: center;
}

.home__skeletons {
  margin-top: 4px;
}

.skeleton-card {
  display: flex;
  gap: 12px;
  padding: 10px 16px;
}

.skeleton-card__poster {
  width: 84px;
  height: 108px;
  flex: 0 0 auto;
  border-radius: 11px;
  background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
  background-size: 200% 100%;
  animation: shimmer 1.6s infinite linear;
}

.skeleton-card__lines {
  flex: 1;
  padding-top: 8px;

  i {
    display: block;
    height: 12px;
    margin-bottom: 10px;
    border-radius: 5px;
    background: linear-gradient(100deg, #17171f 20%, #22222d 38%, #17171f 56%);
    background-size: 200% 100%;
    animation: shimmer 1.6s infinite linear;

    &:nth-child(1) {
      width: 82%;
    }

    &:nth-child(2) {
      width: 54%;
    }

    &:nth-child(3) {
      width: 68%;
    }
  }
}

.city-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(0, 0, 0, 0.58);
  backdrop-filter: blur(4px);
}

.city-sheet {
  width: 100%;
  max-width: 480px;
  padding: 20px 18px calc(22px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: var(--bg-card);
  box-shadow: 0 -20px 50px rgba(0, 0, 0, 0.5);
  animation: slide-up 0.22s ease both;
}

.city-sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h3 {
    margin: 0;
    font-size: 15px;
    font-weight: 800;
  }

  button {
    display: grid;
    place-items: center;
    width: 32px;
    height: 32px;
    border: 0;
    border-radius: 50%;
    background: var(--bg-elevated);
    color: var(--text-2);
    cursor: pointer;
  }
}

.city-sheet__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 9px;

  button {
    height: 42px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: var(--bg-elevated);
    color: var(--text-2);
    font-size: 12px;
    cursor: pointer;

    &.is-active {
      border-color: rgba(255, 61, 113, 0.55);
      background: var(--brand-soft);
      color: var(--brand);
      font-weight: 700;
    }
  }
}

.city-sheet__hint {
  margin: 14px 0 0;
  color: var(--text-3);
  font-size: 9.5px;
  line-height: 1.6;
}

@keyframes shimmer {
  to {
    background-position: -200% 0;
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.45;
  }
}

@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes slide-up {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}
</style>
