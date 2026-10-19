import { reactive } from 'vue'
import { api } from './api'

/**
 * 一个极简的共享状态，替代 Pinia。
 * 站点内容是"一次加载、多个页面共用"，放在这里可以避免每个页面各请求一遍。
 * 如果以后要管购物车那种复杂状态，再引入 Pinia 也不迟。
 */
export const store = reactive({
  siteTitle: '群智感知实验室',
  siteSubtitle: '',
  content: {},
  members: [],
  publications: [],
  loaded: false,
  error: '',

  async loadSite() {
    try {
      const [content, members, publications] = await Promise.all([
        api.labContent(),
        api.labMembers(),
        api.labPublications()
      ])
      this.content = content || {}
      this.members = members || []
      this.publications = publications || []
      this.siteTitle = this.content['home.title'] || this.siteTitle
      this.siteSubtitle = this.content['home.subtitle'] || ''
      this.error = ''
    } catch (e) {
      this.error = e.message
    } finally {
      this.loaded = true
    }
  }
})
