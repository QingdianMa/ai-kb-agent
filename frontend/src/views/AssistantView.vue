<template>
  <div class="page">
    <h1 class="page-title">智能助理</h1>
    <p class="page-subtitle">基于实验室公开资料回答，每条结论都附原文出处</p>

    <section class="card-block">
      <div class="ask-row">
        <el-input
          v-model="question"
          size="large"
          placeholder="例如：实验室主要研究什么方向？"
          :disabled="asking"
          @keyup.enter="ask"
        />
        <el-button type="primary" size="large" :loading="asking" @click="ask">提问</el-button>
      </div>
      <div class="suggestions">
        <span class="hint">试试：</span>
        <el-tag
          v-for="item in suggestions"
          :key="item"
          class="suggestion"
          :disable-transitions="true"
          @click="askWith(item)"
        >
          {{ item }}
        </el-tag>
      </div>
    </section>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon class="error-alert" />

    <section v-if="answer || asking" class="card-block">
      <div class="answer" v-html="renderedAnswer"></div>
      <p class="timing">{{ timing }}</p>
    </section>

    <section v-if="sources.length" class="card-block">
      <h3 class="sources-title">引用来源</h3>
      <div v-for="source in sources" :key="source.index" class="source">
        <p class="source-head">
          <b>[{{ source.index }}]</b>
          {{ source.fileName }}
          <span v-if="source.section" class="section">｜{{ source.section }}</span>
          <span class="score">相关度 {{ scoreText(source.score) }}</span>
        </p>
        <p class="snippet">{{ source.snippet }}</p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue'
import { getAdminToken } from '../api'
import { renderMarkdown } from '../markdown'

const suggestions = [
  '实验室主要研究什么方向？',
  '最近有哪些代表性成果？',
  '团队有哪些成员？',
  '怎么联系实验室？'
]

const question = ref('')
const answer = ref('')
const sources = ref([])
const timing = ref('')
const error = ref('')
const asking = ref(false)

// 边流式输出边渲染：让**加粗**、列表这些标记当场生效，而不是等回答结束才变
const renderedAnswer = computed(() => {
  // 流式过程中屏蔽掉还没闭合的标记，避免中途闪出 ** 这样的符号
  const html = renderMarkdown(answer.value, { streaming: asking.value })
  return asking.value ? html + '<span class="caret">▍</span>' : html
})

let controller = null

function scoreText(score) {
  if (typeof score !== 'number') return '-'
  // 向量相似度、BM25 分、重排分的量纲不同，统一按数值展示，不硬套成百分比
  return score <= 1 ? score.toFixed(3) : score.toFixed(1)
}

function askWith(text) {
  question.value = text
  ask()
}

function stop() {
  if (controller) {
    controller.abort()
    controller = null
  }
  asking.value = false
}

onBeforeUnmount(stop)

async function ask() {
  const text = question.value.trim()
  if (!text || asking.value) return

  stop()
  answer.value = ''
  sources.value = []
  error.value = ''
  asking.value = true
  timing.value = '正在检索…'

  const startedAt = Date.now()
  let firstDeltaAt = null
  controller = new AbortController()

  try {
    // 这里用 fetch 自己解析 SSE，而不是 EventSource。
    // 原因：EventSource 拿不到 HTTP 状态码，401（口令错误）和 429（限流）
    // 都只会变成一句"连接错误"，用户不知道为什么失败。fetch 可以读到状态码和错误消息。
    const headers = {}
    const token = getAdminToken()
    if (token) headers['X-Admin-Token'] = token

    const res = await fetch('/api/chat/stream?question=' + encodeURIComponent(text), {
      headers,
      signal: controller.signal
    })

    if (res.status === 429) {
      throw new Error('提问太频繁了，请稍等一会儿再试')
    }
    if (res.status === 401) {
      throw new Error('需要访问口令')
    }
    if (!res.ok) {
      throw new Error(`服务返回 ${res.status}`)
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let boundary
      while ((boundary = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        if (frame.trim()) {
          handleFrame(frame, () => {
            if (firstDeltaAt === null) {
              firstDeltaAt = Date.now()
              timing.value = `首字 ${((firstDeltaAt - startedAt) / 1000).toFixed(2)} 秒`
            }
          })
        }
      }
    }

    const total = ((Date.now() - startedAt) / 1000).toFixed(2)
    const first = firstDeltaAt === null ? '-' : ((firstDeltaAt - startedAt) / 1000).toFixed(2)
    timing.value = `首字 ${first} 秒 ｜ 总耗时 ${total} 秒`
  } catch (e) {
    if (e.name !== 'AbortError') {
      error.value = e.message
      timing.value = ''
    }
  } finally {
    asking.value = false
    controller = null
  }
}

function handleFrame(frame, markFirstToken) {
  let eventName = 'message'
  const dataLines = []

  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!dataLines.length) return
  const payload = dataLines.join('\n')

  if (eventName === 'sources') {
    try {
      sources.value = JSON.parse(payload)
    } catch {
      sources.value = []
    }
  } else if (eventName === 'delta') {
    markFirstToken()
    answer.value += payload
  } else if (eventName === 'error') {
    error.value = '生成失败，请稍后重试'
  }
}
</script>

<style scoped>
.ask-row {
  display: flex;
  gap: 12px;
}

.suggestions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.hint {
  color: var(--lab-muted);
  font-size: 13px;
}

.suggestion {
  cursor: pointer;
}

.error-alert {
  margin-bottom: 18px;
}

.answer {
  font-size: 15px;
  line-height: 1.8;
}

/* 回答是 v-html 插进来的，样式要用 :deep 才能穿透到子元素 */
.answer :deep(p) {
  margin: 0 0 12px;
}

.answer :deep(p:last-child) {
  margin-bottom: 0;
}

.answer :deep(strong) {
  color: var(--lab-primary-dark);
}

/* 中文没有真正的斜体，浏览器合成的倾斜很难看——强调就不倾斜，改用加粗加色 */
.answer :deep(em) {
  font-style: normal;
  font-weight: 600;
  color: var(--lab-primary-dark);
}

.answer :deep(h4) {
  margin: 0 0 10px;
  font-size: 15.5px;
  color: var(--lab-primary-dark);
}

.answer :deep(ul),
.answer :deep(ol) {
  margin: 0 0 12px;
  padding-left: 22px;
}

.answer :deep(li) {
  margin-bottom: 6px;
}

.answer :deep(code) {
  background: #f2f5f9;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 13.5px;
  font-family: "Cascadia Mono", Consolas, monospace;
}

.answer :deep(.cite) {
  color: var(--lab-primary);
  font-weight: 600;
}

.caret {
  animation: blink 1s step-start infinite;
  color: var(--lab-primary);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.timing {
  margin: 14px 0 0;
  color: var(--lab-muted);
  font-size: 13px;
}

.sources-title {
  font-size: 15px;
  margin: 0 0 12px;
  color: var(--lab-muted);
}

.source {
  padding: 10px 0;
  border-bottom: 1px dashed #e8edf4;
  font-size: 13px;
}

.source:last-child {
  border-bottom: none;
}

.source-head {
  margin: 0 0 4px;
}

.source-head b {
  color: var(--lab-primary);
}

.section {
  color: var(--lab-muted);
}

.score {
  float: right;
  color: #a6adba;
}

.snippet {
  margin: 0;
  color: var(--lab-muted);
  line-height: 1.6;
}

@media (max-width: 640px) {
  /* 窄屏上输入框和按钮并排会挤成一条，改成上下排列 */
  .ask-row {
    flex-direction: column;
  }

  .ask-row .el-button {
    width: 100%;
  }

  .score {
    float: none;
    display: block;
    margin-top: 2px;
  }
}
</style>
