// 深色主题定稿验收：三主题对比度检查。
// 从 frontend/src/design/tokens.css 解析 :root / [data-theme="dark"] / [data-theme="graphite"]
// 三组令牌（后两组未重定义的令牌继承 :root），计算 WCAG 相对亮度对比度并断言：
//   正文组合 --text-1 / --text-2 on --bg-base / --bg-card / --bg-raised ≥ 4.5:1
//   主按钮白字 on --primary-btn ≥ 4.5:1（hover/active 一并输出；浅色 --primary-btn 为 var(--theme)，取默认团队蓝 #007AFF 计算）
// rgba 文字/背景按背景合成后计算。浅色主题不达标只警告不失败（定稿口径：浅色只报告不改色），
// 深色两主题不达标 exit 1。
// 用法：node tools/theme/check-contrast.mjs
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const css = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '../../frontend/src/design/tokens.css'), 'utf8')

function blockVars(selectorRe) {
  const out = {}
  const re = new RegExp(selectorRe.source + '\\{([^}]*)\\}', 'g')
  let m
  while ((m = re.exec(css))) {
    for (const decl of m[1].split(';')) {
      const mm = decl.match(/^\s*(--[\w-]+)\s*:\s*(.+?)\s*$/)
      if (mm) out[mm[1]] = mm[2]
    }
  }
  return out
}
const light = blockVars(/:root(?![\[:])/)
const dark = { ...light, ...blockVars(/:root\[data-theme="dark"\]/) }
const graphite = { ...light, ...blockVars(/:root\[data-theme="graphite"\]/) }

function resolve(vars, name, seen = new Set()) {
  let v = vars[name]
  if (v == null) throw new Error(`令牌缺失 ${name}`)
  let guard = 0
  while (/var\(--[\w-]+\)/.test(v) && guard++ < 10) {
    v = v.replace(/var\((--[\w-]+)\)/g, (_, n) => {
      if (seen.has(n)) throw new Error(`令牌循环引用 ${n}`)
      return resolve(vars, n, new Set([...seen, n]))
    })
  }
  return v.trim()
}
function parseColor(s) {
  s = s.trim()
  let m = s.match(/^#([0-9a-fA-F]{6})$/)
  if (m) { const n = parseInt(m[1], 16); return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255, a: 1 } }
  m = s.match(/^#([0-9a-fA-F]{3})$/)
  if (m) { const [r, g, b] = m[1].split('').map((c) => parseInt(c + c, 16)); return { r, g, b, a: 1 } }
  m = s.match(/^rgba?\(([^)]*)\)$/)
  if (m) { const p = m[1].split(',').map((x) => parseFloat(x)); return { r: p[0], g: p[1], b: p[2], a: p[3] ?? 1 } }
  throw new Error(`无法解析颜色 ${s}`)
}
function over(fg, bg) { return { r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a), a: 1 } }
function lum({ r, g, b }) {
  const f = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4) }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}
function contrast(fgS, bgS) {
  const bg = parseColor(bgS)
  const fg = over(parseColor(fgS), bg)
  const [l1, l2] = [lum(fg), lum(bg)].sort((a, b) => b - a)
  return (l1 + 0.05) / (l2 + 0.05)
}

const themes = [['light（浅色）', light, false], ['dark（暮光蓝灰）', dark, true], ['graphite（石墨）', graphite, true]]
let failed = 0
for (const [label, vars, enforce] of themes) {
  // --primary-btn 浅色为 var(--theme)（团队色），默认团队蓝与 tokens 中 --theme 初值一致
  const rows = []
  for (const bgName of ['--bg-base', '--bg-card', '--bg-raised']) {
    for (const fgName of ['--text-1', '--text-2']) {
      rows.push([`${fgName} on ${bgName}`, contrast(resolve(vars, fgName), resolve(vars, bgName)), 4.5])
    }
  }
  for (const btn of ['--primary-btn', '--primary-btn-hover', '--primary-btn-active']) {
    rows.push([`#FFFFFF on ${btn}`, contrast('#FFFFFF', resolve(vars, btn)), 4.5])
  }
  console.log(`\n== ${label} ==`)
  for (const [name, v, min] of rows) {
    const ok = v >= min
    if (!ok && enforce) failed++
    console.log(`${ok ? 'PASS' : enforce ? 'FAIL' : 'WARN'} ${name}: ${v.toFixed(2)}:1 (≥${min})`)
  }
}
if (failed) { console.error(`\n深色主题对比度断言失败 ${failed} 项`); process.exit(1) }
console.log('\n深色两主题对比度断言全部通过（浅色 WARN 仅报告）')
