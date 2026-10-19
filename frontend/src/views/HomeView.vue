<template>
  <div class="page">
    <!-- 首屏 -->
    <section class="hero">
      <h1>{{ store.siteTitle }}</h1>
      <p class="hero-sub">{{ store.siteSubtitle }}</p>
      <p class="hero-intro">{{ brief(intro, 150) || '内容待补充' }}</p>
      <div class="hero-actions">
        <router-link to="/research">
          <el-button type="primary" size="large" color="#ffffff" style="color: #123c73; font-weight: 600">
            了解研究方向
          </el-button>
        </router-link>
        <router-link to="/assistant">
          <el-button size="large" plain class="ghost-btn">问智能助理</el-button>
        </router-link>
      </div>
    </section>

    <!-- 数据指标 -->
    <section v-if="stats.length" class="stat-grid">
      <div v-for="item in stats" :key="item.label" class="stat">
        <span class="value">{{ item.value }}</span>
        <span class="label">{{ item.label }}</span>
      </div>
    </section>

    <!-- 实验室简介 -->
    <section class="card-block">
      <h2 class="section-title">实验室简介</h2>
      <p class="intro">{{ intro || '内容待补充' }}</p>
      <p v-if="book" class="book-line">
        <span class="chip gold">学术专著</span>
        {{ book }}
      </p>
      <p v-if="patents" class="book-line">
        <span class="chip gold">知识产权</span>
        {{ patents }}
      </p>
    </section>

    <!-- 研究方向 -->
    <section v-if="directions.length" class="block">
      <div class="section-head">
        <h2 class="section-title">研究方向</h2>
        <router-link to="/research" class="more">查看全部 →</router-link>
      </div>
      <div class="card-grid">
        <router-link v-for="(item, index) in directions" :key="item.name" to="/research" class="tile">
          <span class="num">{{ String(index + 1).padStart(2, '0') }}</span>
          <h3>{{ item.name }}</h3>
          <p>{{ brief(item.desc, 62) }}</p>
        </router-link>
      </div>
    </section>

    <!-- 代表性成果 -->
    <section v-if="featured.length" class="block">
      <div class="section-head">
        <h2 class="section-title">代表性成果</h2>
        <router-link to="/publications" class="more">全部 {{ store.publications.length }} 项 →</router-link>
      </div>
      <div class="card-block pub-card">
        <article v-for="item in featured" :key="item.id" class="pub-row">
          <span class="chip">{{ item.year }}</span>
          <div class="pub-main">
            <p class="pub-title">{{ item.title }}</p>
            <p class="pub-meta">{{ item.authors }}</p>
          </div>
          <span class="venue">{{ item.venue }}</span>
        </article>
      </div>
    </section>

    <!-- 获奖与项目 -->
    <section v-if="awards.length || projects.length" class="two-col">
      <div v-if="awards.length" class="card-block">
        <h2 class="section-title">代表性获奖</h2>
        <ul class="line-list">
          <li v-for="(item, index) in awards" :key="index">
            <span class="year">{{ item.year }}</span>
            <span>{{ item.title }}</span>
          </li>
        </ul>
      </div>
      <div v-if="projects.length" class="card-block">
        <h2 class="section-title">主持科研项目</h2>
        <ul class="line-list">
          <li v-for="(item, index) in projects" :key="index">
            <span class="year">{{ item.year }}</span>
            <span>{{ item.title }}</span>
          </li>
        </ul>
      </div>
    </section>

    <!-- 团队 -->
    <section v-if="store.members.length" class="block">
      <div class="section-head">
        <h2 class="section-title">团队成员</h2>
        <router-link to="/team" class="more">查看全部 →</router-link>
      </div>
      <div class="member-strip">
        <router-link
          v-for="member in store.members.slice(0, 6)"
          :key="member.id"
          to="/team"
          class="member-mini"
        >
          <span class="mini-avatar">
            <img v-if="member.photoPath" :src="member.photoPath" :alt="member.name" />
            <template v-else>{{ member.name ? member.name.charAt(0) : '?' }}</template>
          </span>
          <strong>{{ member.name }}</strong>
          <small>{{ member.role }}</small>
        </router-link>
      </div>
    </section>

    <!-- 助理入口 -->
    <section class="assistant-cta">
      <div>
        <h3>有想了解的问题？</h3>
        <p>智能助理基于实验室公开资料回答，每条结论都附原文出处。</p>
      </div>
      <router-link to="/assistant">
        <el-button type="primary" size="large">开始提问</el-button>
      </router-link>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { store } from '../store'
import { brief, parseLines } from '../content'

const intro = computed(() => store.content['home.intro'] || '')
const book = computed(() => store.content['home.book'] || '')
const patents = computed(() => store.content['home.patents'] || '')

const stats = computed(() => parseLines(store.content['home.stats'], ['value', 'label']))
const directions = computed(() => parseLines(store.content['research.directions'], ['name', 'desc']))
const awards = computed(() => parseLines(store.content['home.awards'], ['year', 'title']))
const projects = computed(() => parseLines(store.content['home.projects'], ['year', 'title']))

// 首页只放前四条代表作，避免首屏太长
const featured = computed(() => store.publications.slice(0, 4))
</script>

<style scoped>
.hero-sub {
  font-weight: 500;
}

.hero-intro {
  margin-top: 10px;
}

.ghost-btn {
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.65);
  color: #fff;
}

.ghost-btn:hover {
  background: rgba(255, 255, 255, 0.22);
  border-color: #fff;
  color: #fff;
}

.block {
  margin-bottom: 22px;
}

.intro {
  margin: 0;
}

.book-line {
  margin: 16px 0 0;
  padding-top: 14px;
  border-top: 1px dashed var(--lab-border);
  font-size: 14px;
  color: var(--lab-muted);
  display: flex;
  gap: 10px;
  align-items: baseline;
  flex-wrap: wrap;
}

.num {
  display: block;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: #b9c8dd;
  margin-bottom: 6px;
}

.pub-card {
  padding: 6px 24px;
}

.pub-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 16px 0;
  border-bottom: 1px solid #f0f3f7;
}

.pub-row:last-child {
  border-bottom: none;
}

.pub-main {
  flex: 1;
  min-width: 0;
}

.pub-title {
  margin: 0;
  font-weight: 500;
  line-height: 1.55;
}

.pub-meta {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--lab-muted);
}

.venue {
  flex: none;
  max-width: 220px;
  text-align: right;
  font-size: 13px;
  color: var(--lab-primary);
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 18px;
  margin-bottom: 22px;
}

.two-col .card-block {
  margin-bottom: 0;
}

.line-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.line-list li {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  font-size: 14px;
  border-bottom: 1px solid #f4f6fa;
  line-height: 1.6;
}

.line-list li:last-child {
  border-bottom: none;
}

.line-list .year {
  flex: none;
  color: var(--lab-accent);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.member-strip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 14px;
}

.member-mini {
  background: #fff;
  border: 1px solid var(--lab-border);
  border-radius: var(--lab-radius);
  padding: 18px 12px;
  text-align: center;
  text-decoration: none;
  color: inherit;
  box-shadow: var(--lab-shadow);
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
}

.member-mini:hover {
  transform: translateY(-3px);
  box-shadow: var(--lab-shadow-hover);
  border-color: #c9dcf5;
}

.mini-avatar {
  width: 58px;
  height: 58px;
  border-radius: 50%;
  overflow: hidden;
  background: var(--lab-primary-soft);
  color: var(--lab-primary);
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 10px;
}

.mini-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.member-mini strong {
  display: block;
  font-size: 15px;
}

.member-mini small {
  color: var(--lab-muted);
  font-size: 12.5px;
}

.assistant-cta {
  background: linear-gradient(135deg, #123c73 0%, #1b5aa6 100%);
  color: #fff;
  border-radius: 16px;
  padding: 28px 30px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.assistant-cta h3 {
  margin: 0 0 6px;
  font-size: 19px;
}

.assistant-cta p {
  margin: 0;
  font-size: 14px;
  opacity: 0.9;
}

@media (max-width: 900px) {
  .two-col {
    grid-template-columns: 1fr;
  }

  .pub-row {
    flex-wrap: wrap;
  }

  .venue {
    max-width: none;
    text-align: left;
    flex-basis: 100%;
    padding-left: 0;
  }
}

@media (max-width: 640px) {
  .pub-card {
    padding: 4px 18px;
  }

  .pub-title {
    font-size: 14.5px;
  }

  .assistant-cta {
    padding: 22px 20px;
  }
}
</style>
