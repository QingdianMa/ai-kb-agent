<template>
  <div class="page">
    <h1 class="page-title">研究方向</h1>
    <p class="page-subtitle">面向城市级与空天一体化的感知数据采集、任务分配与优化问题</p>

    <section class="card-block" v-if="intro">
      <p class="intro">{{ intro }}</p>
    </section>

    <section v-if="directions.length" class="direction-list">
      <article v-for="(item, index) in directions" :key="item.name" class="direction">
        <span class="index">{{ String(index + 1).padStart(2, '0') }}</span>
        <div>
          <h2>{{ item.name }}</h2>
          <p>{{ item.desc }}</p>
        </div>
      </article>
    </section>

    <section v-if="methods.length" class="card-block">
      <h2 class="section-title">核心方法</h2>
      <div class="method-tags">
        <span v-for="item in methods" :key="item" class="chip">{{ item }}</span>
      </div>
    </section>

    <section class="card-block">
      <div class="section-head">
        <h2 class="section-title">相关成果</h2>
        <router-link to="/publications" class="more">全部成果 →</router-link>
      </div>
      <ul class="pub-list">
        <li v-for="item in store.publications.slice(0, 5)" :key="item.id">
          <span class="year">{{ item.year }}</span>
          <span class="pub-text">{{ item.title }}</span>
          <span class="venue">{{ item.venue }}</span>
        </li>
        <li v-if="!store.publications.length" class="empty">内容待补充</li>
      </ul>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { store } from '../store'
import { parseLines } from '../content'

const intro = computed(() => store.content['research.intro'] || '')
const directions = computed(() => parseLines(store.content['research.directions'], ['name', 'desc']))

// 核心方法放在一行标签里，比再铺一段文字更容易扫读
const methods = ['多目标优化', '演化计算', '强化学习', '博弈论', '隐私保护']
</script>

<style scoped>
.intro {
  margin: 0;
  white-space: pre-wrap;
}

.direction-list {
  display: grid;
  gap: 14px;
  margin-bottom: 18px;
}

.direction {
  display: flex;
  gap: 18px;
  background: #fff;
  border: 1px solid var(--lab-border);
  border-radius: var(--lab-radius);
  padding: 22px 24px;
  box-shadow: var(--lab-shadow);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.direction:hover {
  border-color: #c9dcf5;
  box-shadow: var(--lab-shadow-hover);
}

.index {
  flex: none;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--lab-primary-soft);
  color: var(--lab-primary);
  font-weight: 700;
  font-size: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.direction h2 {
  margin: 0 0 6px;
  font-size: 17px;
  color: var(--lab-primary-dark);
}

.direction p {
  margin: 0;
  color: var(--lab-muted);
  font-size: 14.5px;
}

.method-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.pub-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.pub-list li {
  display: flex;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px solid #f0f3f7;
  font-size: 14px;
}

.pub-list li:last-child {
  border-bottom: none;
}

.year {
  flex: none;
  color: var(--lab-primary);
  font-weight: 600;
  min-width: 44px;
  font-variant-numeric: tabular-nums;
}

.pub-text {
  flex: 1;
  min-width: 0;
}

.venue {
  flex: none;
  max-width: 240px;
  text-align: right;
  color: var(--lab-muted);
  font-size: 13px;
}

@media (max-width: 640px) {
  .direction {
    padding: 18px 18px;
    gap: 14px;
  }

  .pub-list li {
    flex-wrap: wrap;
  }

  .venue {
    max-width: none;
    text-align: left;
    flex-basis: 100%;
    padding-left: 56px;
  }
}
</style>
