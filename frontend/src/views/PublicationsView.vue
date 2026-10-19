<template>
  <div class="page">
    <h1 class="page-title">研究成果</h1>
    <p class="page-subtitle">已收录 {{ store.publications.length }} 项代表性成果</p>

    <p v-if="scholarUrl" class="scholar-line">
      完整论文列表（117 余篇）请见
      <a :href="scholarUrl" target="_blank" rel="noopener">Google Scholar 学术主页 →</a>
    </p>

    <section v-if="highlights.length" class="stat-grid">
      <div v-for="item in highlights" :key="item.label" class="stat">
        <span class="value">{{ item.value }}</span>
        <span class="label">{{ item.label }}</span>
      </div>
    </section>

    <section v-for="group in grouped" :key="group.year" class="card-block">
      <div class="year-head">
        <h2 class="section-title">{{ group.year }}</h2>
        <span class="count">{{ group.items.length }} 项</span>
      </div>
      <ol class="pub-list">
        <li v-for="(item, index) in group.items" :key="item.id">
          <span class="index">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="pub-body">
            <p class="title">
              <a v-if="item.url" :href="item.url" target="_blank" rel="noopener">{{ item.title }}</a>
              <span v-else>{{ item.title }}</span>
            </p>
            <p class="authors">{{ item.authors }}</p>
            <p class="venue-line"><span class="chip">{{ item.venue }}</span></p>
          </div>
        </li>
      </ol>
    </section>

    <p v-if="!store.publications.length" class="empty">成果信息待补充</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { store } from '../store'
import { parseLines } from '../content'

const grouped = computed(() => {
  const map = new Map()
  for (const item of store.publications) {
    const year = item.year || '其他'
    if (!map.has(year)) map.set(year, [])
    map.get(year).push(item)
  }
  return [...map.entries()]
    .sort((a, b) => String(b[0]).localeCompare(String(a[0])))
    .map(([year, items]) => ({ year, items }))
})

const highlights = computed(() => parseLines(store.content['home.stats'], ['value', 'label']))
const scholarUrl = computed(() => store.content['publications.scholarUrl'] || '')
</script>

<style scoped>
.scholar-line {
  margin: -14px 0 22px;
  font-size: 14px;
  color: var(--lab-muted);
}

.scholar-line a {
  color: var(--lab-primary);
  text-decoration: none;
  border-bottom: 1px solid #cfe0f6;
}

.scholar-line a:hover {
  border-bottom-color: var(--lab-primary);
}

.year-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.year-head .section-title {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.count {
  color: var(--lab-muted);
  font-size: 13px;
}

.pub-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.pub-list li {
  display: flex;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid #f0f3f7;
}

.pub-list li:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.index {
  flex: none;
  width: 30px;
  font-size: 13px;
  font-weight: 700;
  color: #b9c8dd;
  padding-top: 3px;
  font-variant-numeric: tabular-nums;
}

.pub-body {
  min-width: 0;
}

.title {
  margin: 0;
  font-weight: 500;
  line-height: 1.6;
}

.title a {
  color: inherit;
  text-decoration: none;
  border-bottom: 1px solid #d8e2f0;
}

.title a:hover {
  color: var(--lab-primary);
  border-bottom-color: var(--lab-primary);
}

.authors {
  margin: 6px 0 8px;
  color: var(--lab-muted);
  font-size: 13.5px;
}

.venue-line {
  margin: 0;
}

@media (max-width: 640px) {
  .pub-list li {
    gap: 10px;
  }

  .index {
    width: 22px;
  }
}
</style>
