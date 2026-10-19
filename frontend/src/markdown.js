/**
 * 极简 Markdown 渲染器，只服务于"智能助理的回答"这一处。
 *
 * 为什么不用 marked / markdown-it：
 * 回答里出现的只有加粗、列表、行内代码这几种标记，专门引一个库不划算；
 * 更重要的是**安全**——先把 HTML 转义，再按白名单加上我们自己的标签，
 * 模型就算吐出 <script> 也只会被当成普通文字显示。
 *
 * 支持：**加粗**、`行内代码`、- 无序列表、1. 有序列表、#### 小标题、[1] 引用角标
 */

const ESCAPE_MAP = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;'
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (ch) => ESCAPE_MAP[ch])
}

/** 行内标记。输入必须是已经转义过的文本 */
function inline(text) {
  return text
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    // 先处理成对的 **加粗**，再处理单个 *斜体*，顺序反了会把粗体拆成两层斜体
    .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*\n]+)\*/g, '<em>$1</em>')
    .replace(/\[(\d+)\]/g, '<span class="cite">[$1]</span>')
}

/**
 * 流式输出时最后一个标记可能只到了一半——比如刚收到 "**结论："，
 * 闭合的两个星号还在路上。这时直接渲染会让用户看到光秃秃的 **。
 * 做法是临时把未闭合的开标记去掉，等它补全了再按加粗渲染。
 */
function dropDanglingMarkers(text) {
  let source = text
  const boldCount = (source.match(/\*\*/g) || []).length
  if (boldCount % 2 === 1) {
    const last = source.lastIndexOf('**')
    source = source.slice(0, last) + source.slice(last + 2)
  }
  const italicCount = (source.match(/\*/g) || []).length
  if (italicCount % 2 === 1) {
    const last = source.lastIndexOf('*')
    source = source.slice(0, last) + source.slice(last + 1)
  }
  return source
}

export function renderMarkdown(text, options = {}) {
  if (!text) return ''
  const source = options.streaming ? dropDanglingMarkers(text) : text

  const out = []
  let list = null
  let paragraph = []

  const flushParagraph = () => {
    if (paragraph.length) {
      out.push(`<p>${inline(paragraph.join('<br>'))}</p>`)
      paragraph = []
    }
  }
  const closeList = () => {
    if (list) {
      out.push(`</${list}>`)
      list = null
    }
  }

  for (const raw of escapeHtml(source).split('\n')) {
    const line = raw.trim()

    if (!line) {
      flushParagraph()
      closeList()
      continue
    }

    // 列表项。模型的写法很不统一，实测至少有这几种：
    //   "- 内容" / "-内容" / "· 内容" / "• 内容" / "– 内容"，编号还有 "1." "1、" "1)" "（1）"
    // 所以标记字符和空白都放宽；但"-30万"这类紧贴数字的不能当列表，
    // 靠 (?![0-9]) 把这种情况排掉
    // 有空格时 `*` 才算列表符；紧贴文字时不行——`*结论：*` 里的第一个星号
    // 是强调标记的开头，当成列表符会把内容拆坏
    const bullet = /^[-*·•–—－・]\s+(.+)$/.exec(line) || /^[-·•–—－・](?![0-9])(.+)$/.exec(line)
    const ordered = /^\d+[.、)]\s*(.+)$/.exec(line) || /^[（(]\d+[）)]\s*(.+)$/.exec(line)
    if (bullet || ordered) {
      flushParagraph()
      const kind = bullet ? 'ul' : 'ol'
      if (list !== kind) {
        closeList()
        out.push(`<${kind}>`)
        list = kind
      }
      out.push(`<li>${inline(bullet ? bullet[1] : ordered[1])}</li>`)
      continue
    }

    const heading = /^#{1,6}\s+(.*)$/.exec(line)
    if (heading) {
      flushParagraph()
      closeList()
      out.push(`<h4>${inline(heading[1])}</h4>`)
      continue
    }

    paragraph.push(line)
  }

  flushParagraph()
  closeList()
  return out.join('')
}
