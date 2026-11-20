// CI 防写死颜色检查（深色主题定稿验收）：扫描 frontend/src，
// 组件/视图/逻辑代码中出现字面颜色（#hex / rgb() / rgba() / hsl()）即失败。
// 白名单（只许这些地方出现字面颜色）：
//   - design/tokens.css、design/base.css 之外的令牌定义文件：design/tokens.css（全部令牌定义）
//   - components/AiSpark.vue：品牌 AI 星芒渐变（定稿 #FFE36E→#FF9F0A，品牌资产口径）
//   - views/ShowcaseView.vue：组件展示/演示页（正式构建被 __IB_SHOWCASE__ 静态消除，不进 dist）
//   - 数据色板与颜色输入默认值（不是样式写死色，是用户可改的数据）：
//       stores/team.ts 的 THEMES/THEMES_DARK（团队主题色板）、views/TeamsNewView.vue 的 themes 色板、
//       views/TeamSettingsView.vue 的 themeColor 默认值、components/settings/LabelsPanel.vue 的 newColor 默认值、
//       api/mock-adapter.ts 的演示成员头像色（mock 演示数据）
//   - public/ 与 brand SVG 资产不在扫描范围（只扫 src）
// 团队主题色等数据色板如需扩展白名单，在此文件顶部集中登记并写明理由。
// 用法：node tools/theme/check-hardcoded-colors.mjs
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = join(dirname(fileURLToPath(import.meta.url)), '../../frontend/src')
const FILE_WHITELIST = new Set([
  'design/tokens.css',
  'components/AiSpark.vue',
  'views/ShowcaseView.vue',
  'api/mock-adapter.ts',
])
// 数据色板文件：只放行「色板/默认值」行，行内标记登记于此（其余行照常检查）
const DATA_LINE_FILES = new Set(['stores/team.ts', 'views/TeamsNewView.vue', 'views/TeamSettingsView.vue', 'components/settings/LabelsPanel.vue'])
const COLOR_RE = /#[0-9a-fA-F]{3,8}\b|\brgba?\([^)]*\)|\bhsla?\([^)]*\)/

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) yield* walk(p)
    else if (/\.(vue|ts|css)$/.test(name)) yield p
  }
}
// 注释里的色值不是写死颜色（base.css/team.ts 的定稿说明注释曾误报）：
// 扫描前剥注释，保留行号（注释字符替换为空格，换行不动）。
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))
}
let violations = 0
for (const file of walk(SRC)) {
  const rel = relative(SRC, file).split('\\').join('/')
  if (FILE_WHITELIST.has(rel)) continue
  const lines = stripComments(readFileSync(file, 'utf8')).split('\n')
  lines.forEach((line, i) => {
    if (!COLOR_RE.test(line)) return
    if (DATA_LINE_FILES.has(rel) && /(THEMES|themes|themeColor|newColor)/.test(line)) return
    violations++
    console.log(`FAIL ${rel}:${i + 1}: ${line.trim().slice(0, 120)}`)
  })
}
if (violations) { console.error(`\n发现 ${violations} 处写死颜色：请改用 design/tokens.css 令牌（浅色先补同值令牌）`); process.exit(1) }
console.log('无写死颜色（白名单外 0 命中）')
