import { Marked } from 'marked'
import katex from 'katex'
import DOMPurify from 'dompurify'
import 'katex/dist/katex.min.css'

// 助手回答渲染：Markdown + $...$ / $$...$$ 公式（KaTeX）。
// 模型输出视为不可信内容：公式先占位（Unicode 私有区字符，marked 不会转义），
// marked 渲染 Markdown，再把占位替换回 KaTeX HTML，最后 DOMPurify 净化。

const marked = new Marked({ breaks: true })

// 私有区起始符 + 序号 + 结束符：N
const PLACEHOLDER = (n) => `${n}`
const PLACEHOLDER_RE = /(\d+)/g

// 块级 $$...$$ 优先于行内 $...$；未闭合的 $ 保留原文（流式渲染自然渐进）
const BLOCK_MATH = /\$\$([\s\S]+?)\$\$/g
const INLINE_MATH = /(?<!\\)\$(?!\$)([^$\n]+?)(?<!\\)\$(?!\$)/g

/** 把公式段替换为占位符，KaTeX HTML 存入 stash。 */
function stashMath(text) {
  const stash = []
  let out = text.replace(BLOCK_MATH, (m, tex) => {
    stash.push(katex.renderToString(tex.trim(), { displayMode: true, throwOnError: false }))
    return PLACEHOLDER(stash.length - 1)
  })
  out = out.replace(INLINE_MATH, (m, tex) => {
    stash.push(katex.renderToString(tex.trim(), { displayMode: false, throwOnError: false }))
    return PLACEHOLDER(stash.length - 1)
  })
  return { text: out, stash }
}

function escapeFallback(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\n/g, '<br>')
}

/**
 * 渲染助手回答为安全 HTML（可直接 v-html）。
 * 流式中途未闭合的公式原样显示，增量到达闭合符后自动变为排版公式。
 */
export function renderMarkdown(text) {
  if (!text) return ''
  try {
    const { text: stashed, stash } = stashMath(text)
    const html = marked.parse(stashed)
    const restored = html.replace(PLACEHOLDER_RE, (_, i) => stash[Number(i)] ?? '')
    return DOMPurify.sanitize(restored, {
      ADD_TAGS: ['annotation', 'semantics'],
      ADD_ATTR: ['encoding'],
    })
  } catch {
    return escapeFallback(text)
  }
}
