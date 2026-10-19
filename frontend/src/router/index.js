import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('@/views/HomeView.vue') },
  { path: '/research', name: 'research', component: () => import('@/views/ResearchView.vue') },
  { path: '/team', name: 'team', component: () => import('@/views/TeamView.vue') },
  { path: '/publications', name: 'publications', component: () => import('@/views/PublicationsView.vue') },
  { path: '/assistant', name: 'assistant', component: () => import('@/views/AssistantView.vue') },
  // 管理后台用路由懒加载：访客不会下载这部分代码，只有真的访问 /admin 才加载
  { path: '/admin', name: 'admin', component: () => import('@/admin/AdminView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

// 用 hash 模式（地址形如 /#/research），省掉服务端的 SPA 转发配置。
// 代价是 URL 不够干净；如果以后要改成 history 模式，需要在后端加转发规则。
export default createRouter({
  history: createWebHashHistory(),
  routes
})
