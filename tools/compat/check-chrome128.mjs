// Chrome 128 兼容门禁（m53zae5y）：用户单位浏览器为 Chrome 128.0.6613.120，
// 前端不得依赖 Chrome 129+ 才有的 CSS/JS 特性。扫描 frontend/src 源码，
// 命中下列 129+ 特性即失败（浮层组件按修订五口径用手动几何计算，不用锚点定位）：
//   - interpolate-size（129）/ calc-size()（129）
//   - CSS 锚点定位 anchor-name / position-anchor / anchor()（125 起草、129 后稳定推广，128 不完整）
//   - field-sizing（123 试验、129+ 正式）
//   - ::details-content（131）
//   - JS: RegExp.escape（131+）、Promise.try（132+）、Iterator.prototype.* helpers（131+）
// 用法：node tools/compat/check-chrome128.mjs
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../frontend/src')
const RULES = [
  [/interpolate-size/, 'interpolate-size（Chrome 129+）'],
  [/calc-size\s*\(/, 'calc-size()（Chrome 129+）'],
  [/anchor-name\s*:/, 'anchor-name（CSS 锚点定位，128 不完整）'],
  [/position-anchor\s*:/, 'position-anchor（CSS 锚点定位，128 不完整）'],
  [/anchor\s*\(/, 'anchor()（CSS 锚点定位，128 不完整）'],
  [/field-sizing\s*:/, 'field-sizing（Chrome 129+ 正式）'],
  [/::details-content/, '::details-content（Chrome 131+）'],
  [/RegExp\.escape/, 'RegExp.escape（Chrome 131+）'],
  [/Promise\.try/, 'Promise.try（Chrome 132+）'],
  [/Iterator\.prototype/, 'Iterator helpers（Chrome 131+）'],
]
function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) yield* walk(p)
    else if (/\.(vue|ts|css)$/.test(name)) yield p
  }
}
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))
}
let violations = 0
for (const file of walk(SRC)) {
  const rel = relative(SRC, file).split('\\').join('/')
  const lines = stripComments(readFileSync(file, 'utf8')).split('\n')
  lines.forEach((line, i) => {
    for (const [re, label] of RULES) {
      if (re.test(line)) {
        violations++
        console.log(`FAIL ${rel}:${i + 1}: ${label} :: ${line.trim().slice(0, 100)}`)
      }
    }
  })
}
if (violations > 0) {
  console.log(`\n发现 ${violations} 处 Chrome 129+ 特性，Chrome 128 兼容门禁不通过`)
  process.exit(1)
}
console.log('Chrome 128 兼容检查通过（0 处 129+ 特性）')
