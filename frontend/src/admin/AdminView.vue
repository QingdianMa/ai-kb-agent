<template>
  <div class="page">
    <!-- 未登录：输入管理员口令 -->
    <section v-if="!authed" class="card-block login-box">
      <h1 class="page-title">管理入口</h1>
      <p class="page-subtitle">请输入管理员口令</p>
      <div class="login-row">
        <el-input
          v-model="tokenInput"
          type="password"
          show-password
          size="large"
          placeholder="管理员口令"
          @keyup.enter="login"
        />
        <el-button type="primary" size="large" :loading="checking" @click="login">进入</el-button>
      </div>
      <p class="login-hint">本地开发未配置管理员口令时，留空直接点「进入」即可</p>
      <p v-if="loginError" class="login-error">{{ loginError }}</p>
    </section>

    <template v-else>
      <div class="admin-header">
        <h1 class="page-title">管理后台</h1>
        <el-button text @click="logout">退出</el-button>
      </div>

      <el-tabs v-model="tab">
        <!-- ========== 团队成员 ========== -->
        <el-tab-pane label="团队成员" name="members">
          <div class="toolbar">
            <el-button type="primary" @click="openMemberDialog()">新增成员</el-button>
            <el-button link @click="toggleAllMembers">
              {{ allMembersSelected ? '取消全选' : '全选' }}
            </el-button>
            <el-button type="danger" plain :disabled="!selectedMembers.length" @click="removeSelectedMembers">
              删除选中（{{ selectedMembers.length }}）
            </el-button>
            <span class="tip">照片建议 400×400 以内、大小不超过 2MB</span>
          </div>
          <el-table
            ref="memberTableRef"
            :data="members"
            border
            stripe
            @selection-change="onMemberSelectionChange"
          >
            <el-table-column type="selection" width="45" />
            <el-table-column prop="name" label="姓名" width="110" />
            <el-table-column prop="role" label="身份" width="110" />
            <el-table-column prop="title" label="职称 / 职务" />
            <el-table-column prop="email" label="邮箱" width="200" />
            <el-table-column prop="sortOrder" label="排序" width="70" />
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" @click="openMemberDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="removeMember(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== 站点内容 ========== -->
        <el-tab-pane label="站点内容" name="content">
          <el-form label-width="120px" label-position="top">
            <el-form-item v-for="field in contentFields" :key="field.key" :label="field.label">
              <el-input
                v-model="contentForm[field.key]"
                :type="field.rows > 1 ? 'textarea' : 'text'"
                :rows="field.rows"
                :placeholder="field.hint"
              />
            </el-form-item>
          </el-form>
          <el-button type="primary" :loading="saving" @click="saveContent">保存站点内容</el-button>
        </el-tab-pane>

        <!-- ========== 知识库文档 ========== -->
        <el-tab-pane label="知识库文档" name="documents">
          <div class="toolbar">
            <input ref="fileInput" type="file" accept=".pdf,.docx,.md,.markdown,.txt" hidden @change="uploadDocument" />
            <el-button type="primary" @click="fileInput.click()">上传文档</el-button>
            <el-button :loading="ingesting" @click="ingestDocs">同步知识库目录</el-button>
            <el-button :loading="evaluating" @click="runEval">跑消融实验</el-button>
          </div>
          <div class="toolbar">
            <el-button link @click="toggleAllDocuments">
              {{ allDocumentsSelected ? '取消全选' : '全选' }}
            </el-button>
            <el-button type="danger" plain :disabled="!selectedDocuments.length" @click="removeSelectedDocuments">
              删除选中（{{ selectedDocuments.length }}）
            </el-button>
            <span class="tip">删除会同时清理向量，不影响其他文档</span>
          </div>
          <p v-if="docMessage" class="doc-message">{{ docMessage }}</p>
          <el-table
            ref="documentTableRef"
            :data="documents"
            border
            stripe
            @selection-change="onDocumentSelectionChange"
          >
            <el-table-column type="selection" width="45" />
            <el-table-column prop="fileName" label="文件名" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" width="180">
              <template #default="{ row }">
                <el-progress
                  v-if="row.status === 'PENDING' || row.status === 'PROCESSING'"
                  :percentage="row.progress || 0"
                  :stroke-width="14"
                  text-inside
                />
                <span v-else class="tip">{{ row.stage || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="chunkCount" label="片段数" width="80" />
            <el-table-column prop="errorMessage" label="说明" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeDocument(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <!-- 成员编辑弹窗 -->
    <el-dialog v-model="memberDialog" :title="memberForm.id ? '编辑成员' : '新增成员'" width="620px">
      <el-form :model="memberForm" label-width="90px">
        <el-form-item label="姓名">
          <el-input v-model="memberForm.name" placeholder="必填" />
        </el-form-item>
        <el-form-item label="身份">
          <el-input v-model="memberForm.role" placeholder="教授 / 博士研究生 / 硕士研究生" />
        </el-form-item>
        <el-form-item label="职称职务">
          <el-input v-model="memberForm.title" placeholder="如：博士生导师 · 学院院长" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="memberForm.bio" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="memberForm.email" />
        </el-form-item>
        <el-form-item label="照片">
          <div class="photo-row">
            <img v-if="memberForm.photoPath" :src="memberForm.photoPath" class="photo-preview" alt="照片预览" />
            <el-button :loading="uploading" @click="pickPhoto">选择并上传</el-button>
            <input ref="photoInput" type="file" accept="image/*" hidden @change="uploadPhoto" />
          </div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="memberForm.sortOrder" :min="0" />
          <span class="tip">数字小的排前面</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="memberDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMember">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, clearAdminToken, getAdminToken, setAdminToken } from '../api'
import { store } from '../store'

const authed = ref(!!getAdminToken())
const checking = ref(false)
const tokenInput = ref('')
const loginError = ref('')
const tab = ref('members')

const members = ref([])
const documents = ref([])
const contentForm = reactive({})
const saving = ref(false)
const uploading = ref(false)
const ingesting = ref(false)
const evaluating = ref(false)
const docMessage = ref('')

const memberDialog = ref(false)
const memberForm = reactive({ id: null, name: '', role: '', title: '', bio: '', email: '', photoPath: '', sortOrder: 0 })
const fileInput = ref(null)
const photoInput = ref(null)
const memberTableRef = ref(null)
const documentTableRef = ref(null)
const selectedMembers = ref([])
const selectedDocuments = ref([])
let pollTimer = null

const contentFields = [
  { key: 'home.title', label: '站点标题', rows: 1, hint: '网站主标题' },
  { key: 'home.subtitle', label: '副标题', rows: 1, hint: '如：烟台大学计算机与控制工程学院' },
  { key: 'home.intro', label: '首页简介', rows: 5, hint: '首页展示的实验室简介' },
  {
    key: 'home.stats',
    label: '数据指标',
    rows: 7,
    hint: '每行一条，竖线分隔：数值|说明。例如 117|学术论文（篇）'
  },
  {
    key: 'research.directions',
    label: '研究方向',
    rows: 8,
    hint: '每行一条，竖线分隔：方向名称|一句话说明'
  },
  {
    key: 'home.awards',
    label: '代表性获奖',
    rows: 8,
    hint: '每行一条，竖线分隔：年份|奖项名称'
  },
  {
    key: 'home.projects',
    label: '主持科研项目',
    rows: 8,
    hint: '每行一条，竖线分隔：年份|项目名称'
  },
  { key: 'home.book', label: '学术专著', rows: 3, hint: '书名、作者、出版社' },
  { key: 'home.patents', label: '知识产权', rows: 3, hint: '专利、标准等' },
  { key: 'publications.scholarUrl', label: '完整论文列表链接', rows: 1, hint: '如学术主页地址，留空则不显示' },
  { key: 'footer.icp', label: 'ICP 备案号', rows: 1, hint: '如：京ICP备12345678号-1，留空则不显示' },
  { key: 'footer.police', label: '公安备案号', rows: 1, hint: '如：京公网安备11010102000000号，留空则不显示' },
  { key: 'research.intro', label: '研究方向', rows: 4 },
  { key: 'contact.intro', label: '联系方式', rows: 3 }
]

const allMembersSelected = computed(
  () => members.value.length > 0 && selectedMembers.value.length === members.value.length
)
const allDocumentsSelected = computed(
  () => documents.value.length > 0 && selectedDocuments.value.length === documents.value.length
)

onMounted(() => {
  if (authed.value) {
    refresh()
    startPollingIfNeeded()
  }
})

onBeforeUnmount(stopPolling)

async function login() {
  const token = tokenInput.value.trim()
  checking.value = true
  loginError.value = ''
  // 留空是合法的：本地开发通常没配管理员口令。
  // 如果服务端确实要求口令，下面的请求会返回 401，错误信息会显示出来。
  // 之前这里写了 `if (!token) return`，导致本地留空时点了没反应。
  if (token) {
    setAdminToken(token)
  } else {
    clearAdminToken()
  }
  try {
    // 用一个需要管理员权限的只读请求验证口令是否正确
    await api.documents()
    authed.value = true
    await refresh()
  } catch (e) {
    loginError.value = '进入失败：' + e.message
  } finally {
    checking.value = false
  }
}

function logout() {
  clearAdminToken()
  authed.value = false
  tokenInput.value = ''
}

async function refresh() {
  try {
    const [memberList, content, documentList] = await Promise.all([
      api.labMembers(),
      api.labContent(),
      api.documents()
    ])
    members.value = memberList || []
    documents.value = documentList || []
    contentFields.forEach((field) => {
      contentForm[field.key] = content?.[field.key] || ''
    })
  } catch (e) {
    if (e.message.includes('权限') || e.message.includes('口令')) {
      authed.value = false
    }
    ElMessage.error(e.message)
  }
}

function openMemberDialog(row) {
  Object.assign(memberForm, row
    ? { ...row }
    : { id: null, name: '', role: '', title: '', bio: '', email: '', photoPath: '', sortOrder: members.value.length })
  memberDialog.value = true
}

/**
 * 后台改完内容后，同时刷新「访客页面用的共享数据」。
 * 不刷新的话，store 里还是旧数据——因为单页应用切页面是客户端路由，
 * 不会重新加载页面，App.vue 的 onMounted 也不会再触发。
 */
async function refreshSiteData() {
  members.value = (await api.labMembers()) || []
  await store.loadSite()
}

async function saveMember() {
  if (!memberForm.name?.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  saving.value = true
  try {
    await api.saveMember({ ...memberForm })
    memberDialog.value = false
    ElMessage.success('已保存')
    await refreshSiteData()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function removeMember(row) {
  try {
    await ElMessageBox.confirm(`确定删除成员「${row.name}」吗？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteMember(row.id)
    ElMessage.success('已删除')
    await refreshSiteData()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onMemberSelectionChange(rows) {
  selectedMembers.value = rows
}

function toggleAllMembers() {
  memberTableRef.value?.toggleAllSelection()
}

async function removeSelectedMembers() {
  if (!selectedMembers.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedMembers.value.length} 位成员吗？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  const result = await deleteMany(selectedMembers.value, (item) => api.deleteMember(item.id))
  reportBatchResult(result, '位成员')
  await refreshSiteData()
  selectedMembers.value = []
}

function pickPhoto() {
  photoInput.value.click()
}

async function uploadPhoto(event) {
  const file = event.target.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const uploaded = await uploadRaw(file)
    memberForm.photoPath = uploaded
    ElMessage.success('照片已上传')
  } catch (e) {
    ElMessage.error('上传失败：' + e.message)
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

async function uploadRaw(file) {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch('/api/admin/upload', {
    method: 'POST',
    headers: { 'X-Admin-Token': getAdminToken() },
    body: form
  })
  if (!res.ok) {
    throw new Error((await res.text()).slice(0, 120) || `HTTP ${res.status}`)
  }
  const data = await res.json()
  return data.path
}

async function saveContent() {
  saving.value = true
  try {
    await api.saveContent({ ...contentForm })
    await store.loadSite()
    ElMessage.success('已保存，访客页面已同步更新')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

async function uploadDocument(event) {
  const file = event.target.files?.[0]
  if (!file) return
  docMessage.value = '正在上传…'
  try {
    const result = await api.uploadDocument(file)
    docMessage.value = result.message || '已提交'
    documents.value = (await api.documents()) || []
    // 内容已存在时不会有新任务，就不用轮询了
    if (!result.skipped) {
      startPollingIfNeeded()
    }
  } catch (e) {
    docMessage.value = '上传失败：' + e.message
  } finally {
    event.target.value = ''
  }
}

function statusType(status) {
  if (status === 'READY') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PROCESSING') return 'warning'
  return 'info'
}

function statusText(status) {
  const map = { PENDING: '排队中', PROCESSING: '处理中', READY: '可用', FAILED: '失败' }
  return map[status] || status
}

function hasPending() {
  return documents.value.some((doc) => doc.status === 'PENDING' || doc.status === 'PROCESSING')
}

function startPollingIfNeeded() {
  if (!hasPending() || pollTimer) return
  pollTimer = setInterval(pollOnce, 1000)
  pollOnce()   // 立刻拉一次，不用干等 2 秒
}

async function pollOnce() {
  try {
    documents.value = (await api.documents()) || []
    if (!hasPending()) {
      stopPolling()
      docMessage.value = '全部处理完成'
    }
  } catch {
    stopPolling()
  }
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function removeDocument(row) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.fileName}」及其全部向量吗？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.deleteDocument(row.id)
    documents.value = (await api.documents()) || []
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function onDocumentSelectionChange(rows) {
  selectedDocuments.value = rows
}

function toggleAllDocuments() {
  documentTableRef.value?.toggleAllSelection()
}

async function removeSelectedDocuments() {
  if (!selectedDocuments.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selectedDocuments.value.length} 个文档吗？它们的向量也会一起清理。`,
      '确认',
      { type: 'warning' }
    )
  } catch {
    return
  }
  const result = await deleteMany(selectedDocuments.value, (item) => api.deleteDocument(item.id))
  reportBatchResult(result, '个文档')
  documents.value = (await api.documents()) || []
  selectedDocuments.value = []
}

/**
 * 批量删除：逐个执行，记录成功和失败数。
 * 不用 Promise.all 并发：一是删除要清理向量、有外部调用，
 * 二是并发失败时不好判断哪些成功了，逐个来更可控。
 */
async function deleteMany(items, remove) {
  let ok = 0
  const failed = []
  for (const item of items) {
    try {
      await remove(item)
      ok++
    } catch (e) {
      failed.push(`${item.fileName || item.name || item.id}：${e.message}`)
    }
  }
  return { ok, failed }
}

function reportBatchResult(result, unit) {
  if (result.failed.length === 0) {
    ElMessage.success(`已删除 ${result.ok} ${unit}`)
  } else {
    ElMessage.warning(`删除 ${result.ok} ${unit}，${result.failed.length} 个失败：${result.failed[0]}`)
  }
}

async function ingestDocs() {
  ingesting.value = true
  docMessage.value = '正在同步知识库目录…'
  try {
    const result = await api.ingestDocs()
    const submitted = result.submitted ?? 0
    const skipped = result.skipped?.length ?? 0
    // 按内容哈希去重：没改动的文件会被跳过，所以这个按钮可以反复点
    docMessage.value = submitted > 0
      ? `已提交 ${submitted} 个文件，跳过 ${skipped} 个未改动的`
      : `全部 ${skipped} 个文件都没有变化，无需重新入库`
    documents.value = (await api.documents()) || []
    if (submitted > 0) {
      startPollingIfNeeded()
    }
  } catch (e) {
    docMessage.value = '导入失败：' + e.message
  } finally {
    ingesting.value = false
  }
}

async function runEval() {
  evaluating.value = true
  docMessage.value = '正在跑消融实验，需要几分钟…'
  try {
    const result = await api.runEval()
    const lines = (result.results || [])
      .map((r) => `${r.config}：Coverage@${result.topK}=${r.coverageAtK}  HitRate=${r.hitRateAtK}`)
      .join('　|　')
    docMessage.value = lines || '评测完成'
  } catch (e) {
    docMessage.value = '评测失败：' + e.message
  } finally {
    evaluating.value = false
  }
}
</script>

<style scoped>
.login-box {
  max-width: 460px;
  margin: 60px auto;
}

.login-row {
  display: flex;
  gap: 12px;
}

.login-error {
  color: #d9534f;
  font-size: 13px;
  margin: 12px 0 0;
}

.login-hint {
  color: var(--lab-muted);
  font-size: 13px;
  margin: 12px 0 0;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.tip {
  color: var(--lab-muted);
  font-size: 13px;
  margin-left: 8px;
}

.doc-message {
  color: var(--lab-primary);
  font-size: 13px;
  margin: 0 0 12px;
}

.photo-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.photo-preview {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid #e6eaf0;
}
</style>
