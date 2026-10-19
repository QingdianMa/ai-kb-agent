/**
 * 站点内容里的"列表型"字段统一用这种文本格式：一行一条，字段之间用竖线分隔。
 *
 * 为什么不用 JSON：后台的编辑框就是普通文本框，改内容的人直接写文字最省事；
 * 竖线格式人眼能读、能手改，解析也只有几行代码。
 */
export function parseLines(text, fields) {
  if (!text) return []
  return String(text)
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith('#'))
    .map((line) => {
      const parts = line.split('|').map((part) => part.trim())
      const item = {}
      fields.forEach((name, index) => {
        item[name] = parts[index] || ''
      })
      return item
    })
}

/** 压平并截断长文本，用于卡片摘要 */
export function brief(text, max = 60) {
  if (!text) return ''
  const flat = String(text).replace(/\s+/g, ' ').trim()
  return flat.length > max ? flat.slice(0, max) + '…' : flat
}
