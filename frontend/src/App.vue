<template>
  <header class="site-header">
    <div class="inner">
      <router-link to="/" class="brand" @click="menuOpen = false">
        <span class="mark">群智</span>
        <span class="brand-text">{{ store.siteTitle }}</span>
      </router-link>

      <nav class="nav" :class="{ open: menuOpen }">
        <router-link to="/research" @click="menuOpen = false">研究方向</router-link>
        <router-link to="/team" @click="menuOpen = false">团队成员</router-link>
        <router-link to="/publications" @click="menuOpen = false">研究成果</router-link>
        <router-link to="/assistant" class="highlight" @click="menuOpen = false">智能助理</router-link>
      </nav>

      <button
        class="menu-toggle"
        type="button"
        :aria-expanded="menuOpen"
        aria-label="展开导航"
        @click="menuOpen = !menuOpen"
      >
        <span :class="{ crossed: menuOpen }"></span>
      </button>
    </div>
  </header>

  <main>
    <router-view />
  </main>

  <footer class="site-footer">
    <div class="inner">
      <div class="footer-brand">
        <strong>{{ store.siteTitle }}</strong>
        <span>{{ store.siteSubtitle }}</span>
      </div>
      <nav class="footer-links">
        <router-link to="/research">研究方向</router-link>
        <router-link to="/team">团队成员</router-link>
        <router-link to="/publications">研究成果</router-link>
        <router-link to="/assistant">智能助理</router-link>
      </nav>
      <router-link to="/admin" class="admin-link">管理入口</router-link>
    </div>
    <!-- 备案号按规定要显示在底部，并链接到对应的查询系统 -->
    <div class="legal">
      <span>© {{ year }} {{ store.siteTitle }}</span>
      <a v-if="icp" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">{{ icp }}</a>
      <a v-if="police" :href="policeLink" target="_blank" rel="noopener">{{ police }}</a>
    </div>
  </footer>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { store } from './store'

const menuOpen = ref(false)
const route = useRoute()

const year = computed(() => new Date().getFullYear())
const icp = computed(() => store.content['footer.icp'] || '')
const police = computed(() => store.content['footer.police'] || '')

// 公安备案的查询地址需要单独的数字编号，从备案号里取出来
const policeLink = computed(() => {
  const code = (police.value.match(/\d+/) || [''])[0]
  return `https://beian.mps.gov.cn/#/query/webSearch?code=${code}`
})

// 路由变化后收起移动端菜单，否则点完链接菜单还挂在那儿
watch(() => route.fullPath, () => {
  menuOpen.value = false
})

onMounted(() => {
  if (!store.loaded) {
    store.loadSite()
  }
})
</script>

<style scoped>
.site-header {
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: saturate(180%) blur(12px);
  border-bottom: 1px solid var(--lab-border);
  position: sticky;
  top: 0;
  z-index: 20;
}

.site-header .inner {
  max-width: 1080px;
  margin: 0 auto;
  padding: 0 20px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 17px;
  font-weight: 650;
  color: var(--lab-primary-dark);
  text-decoration: none;
  min-width: 0;
}

.brand .mark {
  flex: none;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: linear-gradient(135deg, #1b5aa6, #2f7fd0);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 26px;
}

.nav a {
  color: var(--lab-muted);
  text-decoration: none;
  font-size: 15px;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
  transition: color 0.15s, border-color 0.15s;
}

.nav a:hover {
  color: var(--lab-primary);
}

.nav a.highlight {
  color: var(--lab-primary);
  font-weight: 600;
}

.nav a.router-link-active {
  color: var(--lab-primary);
  font-weight: 600;
  border-bottom-color: var(--lab-primary);
}

/* 汉堡按钮：只在窄屏出现 */
.menu-toggle {
  display: none;
  flex: none;
  width: 40px;
  height: 40px;
  border: 1px solid var(--lab-border);
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  position: relative;
}

.menu-toggle span,
.menu-toggle span::before,
.menu-toggle span::after {
  position: absolute;
  left: 50%;
  width: 18px;
  height: 2px;
  background: var(--lab-primary-dark);
  border-radius: 2px;
  transform: translateX(-50%);
  transition: transform 0.18s ease, opacity 0.18s ease;
}

.menu-toggle span {
  top: 19px;
}

.menu-toggle span::before,
.menu-toggle span::after {
  content: "";
  left: 0;
  transform: none;
}

.menu-toggle span::before {
  top: -6px;
}

.menu-toggle span::after {
  top: 6px;
}

.menu-toggle span.crossed {
  background: transparent;
}

.menu-toggle span.crossed::before {
  top: 0;
  transform: rotate(45deg);
}

.menu-toggle span.crossed::after {
  top: 0;
  transform: rotate(-45deg);
}

.site-footer {
  border-top: 1px solid var(--lab-border);
  background: #fff;
  margin-top: 40px;
}

.site-footer .inner {
  max-width: 1080px;
  margin: 0 auto;
  height: auto;
  padding: 26px 20px;
  color: var(--lab-muted);
  font-size: 13px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.footer-brand {
  display: flex;
  flex-direction: column;
  line-height: 1.6;
}

.footer-brand strong {
  color: var(--lab-text);
  font-size: 14px;
}

.footer-links {
  display: flex;
  gap: 18px;
  margin-left: auto;
  flex-wrap: wrap;
}

.footer-links a {
  color: var(--lab-muted);
  text-decoration: none;
}

.footer-links a:hover {
  color: var(--lab-primary);
}

.admin-link {
  color: #a6adba;
  text-decoration: none;
  font-size: 13px;
}

.admin-link:hover {
  color: var(--lab-primary);
}

.legal {
  max-width: 1080px;
  margin: 0 auto;
  padding: 0 20px 22px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  font-size: 12.5px;
  color: #9aa3b0;
}

.legal a {
  color: #9aa3b0;
  text-decoration: none;
}

.legal a:hover {
  color: var(--lab-primary);
  text-decoration: underline;
}

@media (max-width: 900px) {
  .menu-toggle {
    display: block;
  }

  .nav {
    position: absolute;
    top: 64px;
    left: 0;
    right: 0;
    flex-direction: column;
    gap: 0;
    background: #fff;
    border-bottom: 1px solid var(--lab-border);
    box-shadow: 0 12px 24px rgba(18, 38, 72, 0.08);
    /* 收起时内边距也要归零，否则表头下面会留一条 20px 的空白带 */
    padding: 0 20px;
    max-height: 0;
    overflow: hidden;
    opacity: 0;
    transition: max-height 0.22s ease, opacity 0.22s ease, padding 0.22s ease;
  }

  .nav.open {
    max-height: 320px;
    opacity: 1;
    padding: 6px 20px 14px;
  }

  .nav a {
    padding: 12px 0;
    border-bottom: 1px solid var(--lab-border);
    font-size: 16px;
  }

  .nav a:last-child {
    border-bottom: none;
  }

  .nav a.router-link-active {
    border-bottom-color: var(--lab-border);
  }

  .footer-links {
    margin-left: 0;
    width: 100%;
  }
}
</style>
