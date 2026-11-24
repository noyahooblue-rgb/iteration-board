// 修订五 A 本地浮层矩阵自测（对 mock 构建产物）：
//   3 窗口 (1366x768 / 1440x900 / 1920x1080) × 缩放 (deviceScaleFactor 1 / 1.25 / 1.5)
//   × 主题（默认浅色 + 暮光蓝灰深色；IB_MATRIX_THEMES=light,dark,graphite 可调），逐个打开主要浮层，断言：
//     1) 浮层外框完整在视口内（距边缘 >= 8px，1px 容差）；
//     2) 每个可交互项中心 elementFromPoint 命中自身（未被裁切/遮挡）。
//   另含更新说明弹窗（通用 Modal）回归用例：外框在视口内 +「知道了」按钮可达
//   （1.1.0 长内容曾把按钮推出屏幕，见 Modal.vue 修复注释）。
// 主题预置：addInitScript 写 localStorage 'ib-appearance'（utils/appearance.ts 的存储键），
// 首屏 initAppearance 即按预置主题生效；深色同时 colorScheme:'dark' 模拟系统深色。
//
// 用法：
//   cd frontend && npm run build:mock          # 先出 mock 构建
//   node tools/local-e2e/overlay-matrix.mjs     # 脚本自起 python3 -m http.server 静态服务
// 环境变量：IB_DIST（默认 frontend/dist）、IB_PORT（默认 18923）、IB_SHOTS（截图目录，可选）
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

let chromium
try { ({ chromium } = await import('playwright')) }
catch { ({ chromium } = await import(join(process.env.HOME, 'workspace/ib-e2e/node_modules/playwright/index.mjs'))) }

const HERE = dirname(fileURLToPath(import.meta.url))
const DIST = process.env.IB_DIST || join(HERE, '../../frontend/dist')
const PORT = Number(process.env.IB_PORT || 18923)
const BASE = `http://127.0.0.1:${PORT}`
const SHOTS = process.env.IB_SHOTS || ''

const server = spawn('python3', ['-m', 'http.server', String(PORT), '--directory', DIST, '--bind', '127.0.0.1'], { stdio: 'ignore' })
process.on('exit', () => server.kill())
await new Promise((r) => setTimeout(r, 1200))

let pass = 0, fail = 0, modalChecks = 0
const failures = []
function check(name, cond, extra = '') {
  if (cond) { pass++; console.log(`  PASS ${name}`) }
  else { fail++; failures.push(`${name} ${extra}`); console.log(`  FAIL ${name} ${extra}`) }
}

async function assertOverlay(page, label, itemSelector) {
  const panel = page.locator('.ovl').last()
  await panel.waitFor({ state: 'visible', timeout: 8000 })
  const box = await panel.boundingBox()
  const vp = page.viewportSize()
  check(`${label} 外框在视口内`, !!box && box.x >= 7.5 && box.y >= 7.5 && box.x + box.width <= vp.width - 7.5 && box.y + box.height <= vp.height - 7.5,
    JSON.stringify(box) + ` vs ${vp.width}x${vp.height}`)
  const items = panel.locator(itemSelector)
  const n = await items.count()
  let hits = 0
  for (let i = 0; i < n; i++) {
    const ok = await items.nth(i).evaluate((el) => {
      const r = el.getBoundingClientRect()
      if (r.width === 0 || r.height === 0) return false
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
      return top === el || el.contains(top) || (top && el.contains(top))
    }).catch(() => false)
    if (ok) hits++
  }
  check(`${label} 菜单项命中 ${hits}/${n}`, n > 0 && hits === n)
  if (SHOTS) await page.screenshot({ path: join(SHOTS, `matrix-${label.replaceAll(/[^a-z0-9]+/gi, '_')}.png`) })
}

// 首登浮层（更新说明弹窗/新手引导）：关最上层按钮（.last()，已验证的坑）
async function dismissOverlays(page) {
  for (let i = 0; i < 6; i++) {
    const btn = page.locator('button', { hasText: /^(知道了|跳过引导|知道了，开始使用)$/ }).last()
    if (await btn.count() === 0 || !(await btn.isVisible().catch(() => false))) return
    await btn.click({ timeout: 3000 }).catch(() => {})
    await page.waitForTimeout(400)
  }
}

async function closeOverlay(page) {
  await page.keyboard.press('Escape')
  await page.waitForTimeout(250)
}

const VIEWPORTS = [
  { width: 1366, height: 768 },
  { width: 1440, height: 900 },
  { width: 1920, height: 1080 },
]
const SCALES = [1, 1.25, 1.5]

const MATRIX_THEMES = (process.env.IB_MATRIX_THEMES || 'light,dark').split(',').map((s) => s.trim()).filter(Boolean)
const THEME_LABEL = { light: '浅色', dark: '暮光蓝灰', graphite: '石墨' }

const browser = await chromium.launch()
try {
  for (const theme of MATRIX_THEMES) {
   for (const vp of VIEWPORTS) {
    for (const dsf of SCALES) {
      const tag = `${theme}-${vp.width}x${vp.height}@${Math.round(dsf * 100)}%`
      console.log(`\n== ${tag} · ${THEME_LABEL[theme] ?? theme} ==`)
      const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: dsf, colorScheme: theme === 'light' ? 'light' : 'dark' })
      await ctx.addInitScript((t) => {
        try { localStorage.setItem('ib-appearance', JSON.stringify({ theme: t, glass: 'standard', reduceTransparency: false, reduceMotion: false })) } catch {}
      }, theme)
      const page = await ctx.newPage()
      await page.goto(BASE + '/', { waitUntil: 'networkidle' })
      // mock 演示登录：点组长演示账号 chip 一键填入，再点登录
      const chip = page.locator('.demo-box .chip').first()
      await chip.waitFor({ state: 'visible', timeout: 15000 })
      await chip.click()
      await page.getByRole('button', { name: '登录', exact: true }).click()
      await page.waitForSelector('aside.sidebar', { timeout: 15000 })
      await page.waitForTimeout(600)
      // 0) 更新说明弹窗（通用 Modal 组件）回归用例：外框完整在视口内 +「知道了」按钮可达。
      //    1.1.0 冻结时说明内容加长，Modal 无 max-height 导致矮视口下按钮被推出屏幕外、
      //    遮罩拦截一切点击（档 3 四个 Playwright 场景实证）；修复后弹窗本体内部滚动，按钮须可达。
      const ackBtn = page.getByRole('button', { name: '知道了', exact: true }).last()
      if (await ackBtn.count()) {
        modalChecks++
        const rnModal = page.locator('.mask .modal').filter({ has: ackBtn })
        const mbox = await rnModal.boundingBox().catch(() => null)
        check(`${tag} 更新说明弹窗外框在视口内`, !!mbox && mbox.x >= -0.5 && mbox.y >= -0.5 && mbox.x + mbox.width <= vp.width + 0.5 && mbox.y + mbox.height <= vp.height + 0.5,
          JSON.stringify(mbox) + ` vs ${vp.width}x${vp.height}`)
        await ackBtn.scrollIntoViewIfNeeded().catch(() => {})
        const hit = await ackBtn.evaluate((el) => {
          const r = el.getBoundingClientRect()
          if (r.width === 0 || r.height === 0) return false
          const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
          return top === el || el.contains(top)
        }).catch(() => false)
        check(`${tag} 更新说明「知道了」按钮可达`, hit)
      }
      await dismissOverlays(page)
      // 窗口 <1440 时侧栏默认收起（既有行为），先展开才能测小组/用户菜单
      if (await page.locator('aside.sidebar.mini').count()) {
        await page.locator('button[title="收起/展开侧栏（Ctrl+B）"]').click()
        await page.waitForTimeout(400)
      }

      // 1) 小组切换菜单
      await page.locator('.team-switch').click()
      await assertOverlay(page, `${tag} 小组菜单`, '.ovl-item, .tm-link')
      await closeOverlay(page)

      // 2) 用户菜单（768 高时该按钮被侧栏内容挤出视口属既有布局，用 evaluate 点击触发））
      await page.locator('button[aria-label="用户菜单"]').evaluate((e) => e.click())
      await assertOverlay(page, `${tag} 用户菜单`, '.ovl-item')
      await closeOverlay(page)

      // 3) 通知面板
      await page.locator('button[aria-label="通知中心"]').click()
      await assertOverlay(page, `${tag} 通知面板`, '.bp-item, .bp-link')
      await closeOverlay(page)

      // 4) 看板事项抽屉：状态下拉（修订五报障点）
      // 注：python http.server 无 SPA 回退，不能直达 /board，走侧栏链接客户端导航
      await page.locator('a[href="/board"]').click()
      await page.waitForTimeout(600)
      await dismissOverlays(page)
      await page.waitForSelector('.kcard', { timeout: 15000 })
      await page.locator('.kcard').first().click()
      await page.waitForSelector('aside.drawer', { timeout: 10000 })
      await page.waitForTimeout(600)
      await page.locator('aside.drawer .pillbtn').click()
      await assertOverlay(page, `${tag} 状态下拉`, '.ovl-item')
      await closeOverlay(page)

      // 5) 抽屉内联浮层：负责人多选
      const ownerRow = page.locator('aside.drawer .attr', { hasText: '负责人' }).first().locator('b.editable')
      if (await ownerRow.count()) {
        await ownerRow.click()
        await assertOverlay(page, `${tag} 负责人选择`, '.poprow, .popfoot button')
        await closeOverlay(page)
      } else {
        console.log('  SKIP 负责人选择（当前账号无内联编辑权限）')
      }

      // 6) @提及列表：进展输入框输入 @
      const ta = page.locator('aside.drawer .dfoot textarea').first()
      if (await ta.count()) {
        await ta.click()
        await ta.fill('@')
        await page.waitForTimeout(500)
        if (await page.locator('.ovl').count()) {
          await assertOverlay(page, `${tag} @提及列表`, '.mrow')
        } else {
          console.log('  SKIP @提及列表（未弹出）')
        }
        await closeOverlay(page)
      }
      await ctx.close()
    }
   }
  }
} finally {
  await browser.close()
  server.kill()
}
check('更新说明弹窗回归用例至少执行一次', modalChecks > 0, `实际执行 ${modalChecks} 次`)
console.log(`\n浮层矩阵结果：${pass} 通过 / ${fail} 失败 / 共 ${pass + fail}`)
if (failures.length) console.log('失败项：\n' + failures.join('\n'))
process.exit(fail ? 1 : 0)
