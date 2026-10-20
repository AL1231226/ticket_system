<script setup>
import { computed } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  size: { type: [Number, String], default: 20 },
  strokeWidth: { type: [Number, String], default: 1.8 },
})

// 自绘线性图标。统一 24 网格，避免 emoji 和默认图标库带来的拼贴感。
const icons = {
  home: ['M3.5 10.5 12 3l8.5 7.5', 'M5.5 9.5V21h13V9.5', 'M9.5 21v-6h5v6'],
  ticket: [
    'M4 7.5A2.5 2.5 0 0 1 6.5 5h11A2.5 2.5 0 0 1 20 7.5v2a2.5 2.5 0 0 0 0 5v2a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5v-2a2.5 2.5 0 0 0 0-5v-2Z',
    'M14 5v2',
    'M14 11v2',
    'M14 17v2',
  ],
  user: ['M20 21a8 8 0 0 0-16 0', 'M12 13a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9Z'],
  'arrow-left': ['M19 12H5', 'm11 18-6-6 6-6'],
  'chevron-right': ['m9 18 6-6-6-6'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 7v5l3 2'],
  calendar: [
    'M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1Z',
    'M4 10h16',
    'M8 3v4',
    'M16 3v4',
  ],
  refresh: [
    'M20 11a8 8 0 0 0-14.9-3M4 4v4h4',
    'M4 13a8 8 0 0 0 14.9 3M20 20v-4h-4',
  ],
  plus: ['M12 5v14', 'M5 12h14'],
  minus: ['M5 12h14'],
  check: ['m5 12 4.5 4.5L19 7'],
  x: ['M6 6l12 12', 'M18 6 6 18'],
  qr: [
    'M4 4h5v5H4z',
    'M15 4h5v5h-5z',
    'M4 15h5v5H4z',
    'M15 15h2v2h-2z',
    'M19 15v2h-2v2h2v2h-4v-2',
    'M15 19h-2v2',
  ],
  users: [
    'M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2',
    'M9.5 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z',
    'M17 11a4 4 0 0 0 0-8',
    'M21 21v-2a4 4 0 0 0-3-3.87',
  ],
  logout: ['M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4', 'M16 17l5-5-5-5', 'M21 12H9'],
  heart: [
    'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1L12 21l7.7-7.6 1.1-1a5.5 5.5 0 0 0 0-7.8Z',
  ],
  location: [
    'M12 21s7-5.5 7-11a7 7 0 1 0-14 0c0 5.5 7 11 7 11Z',
    'M12 12.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z',
  ],
  receipt: ['M6 3h12v18l-3-2-3 2-3-2-3 2V3Z', 'M9 8h6', 'M9 12h6'],
}

const paths = computed(() => icons[props.name] || [])
</script>

<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    :stroke-width="strokeWidth"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
  >
    <path v-for="(d, index) in paths" :key="index" :d="d" />
  </svg>
</template>
