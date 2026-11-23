// Windows runner E2E (8eyn4mwp): drive a real browser DIRECTLY against the
// installed application's own address (http://localhost:18080) - no proxy,
// no mock. Flow: open / -> login page (must be HTML, never a 401 JSON body)
// -> register an account (captcha read from the SVG the page itself
// renders) -> create a team -> create an item -> open /board, refresh it,
// and assert it still renders (not 401/404) -> direct-open /admin and
// /feedback (SPA fallback). Screenshots are kept as delivery evidence:
//   01-home-login.png        home page redirected to the login page
//   02-workbench.png         workbench after login + team creation
//   03-board-after-refresh.png  /board after a full page refresh
//
// Env: IB_BASE (default http://localhost:18080), IB_SHOTS (default ./shots)
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = (process.env.IB_BASE || 'http://localhost:18080').replace(/\/$/, '')
const SHOTS = process.env.IB_SHOTS || './shots'
mkdirSync(SHOTS, { recursive: true })

const LOGIN_NAME = 'ci_admin'
const DISPLAY_NAME = 'CI 管理员'
const PASSWORD = 'CiTest1234@'
const TEAM_NAME = 'CI 验证小组'
const TEAM_CODE = 'CI'
const ITEM_TITLE = 'CI 验证缺陷事项'

function step(msg) { console.log(`[e2e] ${msg}`) }
function fail(msg) { throw new Error(msg) }

// 1.1.0: two first-run overlays (release-notes modal + onboarding guide)
// appear over the workbench and intercept clicks until dismissed. Close
// whichever is visible; safe to call when none is open.
async function dismissOverlays(page) {
  for (let i = 0; i < 6; i++) {
    const btn = page.locator('button', { hasText: /^(知道了|跳过引导|知道了，开始使用)$/ }).last()
    if (await btn.count() === 0 || !(await btn.isVisible().catch(() => false))) return
    await btn.click({ timeout: 3000 }).catch(() => {})
    await page.waitForTimeout(400)
  }
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
try {
  // --- 1. Home page: SPA serves index.html, router lands on the login page.
  const resp = await page.goto(BASE + '/', { waitUntil: 'networkidle' })
  if (!resp || resp.status() !== 200) fail(`GET / returned ${resp && resp.status()}, expected 200`)
  await page.waitForSelector('label.field', { timeout: 15000 })
  const bodyText = await page.locator('body').innerText()
  if (bodyText.includes('UNAUTHORIZED') || bodyText.trim().startsWith('{')) {
    fail('home page body looks like a JSON error instead of the SPA login page')
  }
  // Brand lockup is an <img> since the brand integration (92c9828):
  // the brand name lives in its alt attribute, which innerText does
  // not include. Accept either the visible text or the lockup alt.
  const lockupAlt = await page.locator('img.lockup').getAttribute('alt').catch(() => null)
  if (!bodyText.includes('迭代小组平台') && lockupAlt !== '迭代小组平台') fail('login page brand text not found')
  await page.screenshot({ path: `${SHOTS}/01-home-login.png` })
  step('home page renders the login page (HTML 200, no 401 JSON)')

  // --- 2. Register the first account. Captcha code = the <text> glyphs of
  // the SVG rendered inside button.cap (same page, no external solving).
  // The tab buttons carry role="tab", so select them by CSS + text,
  // not getByRole('button').
  await page.locator('.seg button', { hasText: '注册' }).click()
  await page.locator('label.field').filter({ hasText: '注册后不可改' }).locator('input').fill(LOGIN_NAME)
  await page.locator('label.field').filter({ hasText: '显示姓名' }).locator('input').fill(DISPLAY_NAME)
  await page.locator('label.field').filter({ hasText: '至少 8 位' }).locator('input').fill(PASSWORD)
  const glyphs = await page.locator('button.cap svg text').allTextContents()
  const code = glyphs.join('').trim()
  if (code.length < 4) fail(`captcha glyphs unreadable: "${code}"`)
  await page.locator('label.field').filter({ hasText: '图形验证码' }).locator('input').fill(code)
  await page.getByRole('button', { name: '注册并登录' }).click()
  await page.waitForURL('**/teams/new', { timeout: 20000 })
  step('registered first account and landed on /teams/new')

  // --- 3. Create a team.
  await page.locator('label.field').filter({ hasText: '小组名称' }).locator('input').fill(TEAM_NAME)
  await page.locator('label.field').filter({ hasText: '代号' }).locator('input').fill(TEAM_CODE)
  await page.getByRole('button', { name: '创建小组' }).click()
  await page.waitForURL('**/workbench', { timeout: 20000 })
  await page.waitForLoadState('networkidle')
  await dismissOverlays(page)
  await page.screenshot({ path: `${SHOTS}/02-workbench.png` })
  step('team created, workbench rendered')

  // --- 4. Create an item (defect type: title is the only required field).
  await page.getByRole('button', { name: '新建' }).click()
  await page.waitForSelector('text=新建事项', { timeout: 10000 })
  await page.locator('button.tp', { hasText: '缺陷' }).first().click()
  await page.locator('label.field').filter({ hasText: '标题（必填）' }).locator('input').fill(ITEM_TITLE)
  await page.getByRole('button', { name: '创建事项', exact: true }).click()
  await page.waitForSelector('text=新建事项', { state: 'detached', timeout: 15000 })
  step('item created via the UI')

  // --- 5. /board, then a full refresh: must still render (SPA fallback),
  // never a bare 401 JSON or 404.
  const bresp = await page.goto(BASE + '/board', { waitUntil: 'networkidle' })
  if (!bresp || bresp.status() !== 200) fail(`GET /board returned ${bresp && bresp.status()}, expected 200`)
  if (!(await page.locator('body').innerText()).includes(ITEM_TITLE)) {
    fail('created item not visible on /board')
  }
  const rresp = await page.reload({ waitUntil: 'networkidle' })
  if (!rresp || rresp.status() !== 200) fail(`refresh /board returned ${rresp && rresp.status()}, expected 200`)
  const after = await page.locator('body').innerText()
  if (after.includes('UNAUTHORIZED') || after.trim().startsWith('{')) {
    fail('after refresh /board shows a JSON error instead of the SPA')
  }
  if (!after.includes(ITEM_TITLE)) fail('created item missing on /board after refresh')
  await page.screenshot({ path: `${SHOTS}/03-board-after-refresh.png` })
  step('/board survives a full refresh with the item visible')

  // --- 6. /admin direct open: the SPA fallback list must include /admin
  // (1.0.4 regression: a missing entry returns a JSON 404 instead of
  // index.html). 1.1.0 P01 changed WHO is admin: self-registered accounts
  // are never platform admins any more (only the installer-created
  // built-in "admin" account is), so this account must NOT see the admin
  // view - what is asserted here is the fallback itself (HTTP 200 SPA,
  // never a JSON error). The admin view rendering for the real admin is
  // covered by scenario F (F6b).
  const aresp = await page.goto(BASE + '/admin', { waitUntil: 'load' })
  if (!aresp || aresp.status() !== 200) fail(`GET /admin returned ${aresp && aresp.status()}, expected 200`)
  await page.waitForTimeout(1200)
  const atext = await page.locator('body').innerText()
  if (atext.includes('接口不存在') || atext.trim().startsWith('{')) fail('/admin shows a JSON error instead of the SPA (SPA fallback list missing /admin)')
  await page.screenshot({ path: `${SHOTS}/04-admin.png` })
  step('/admin direct open serves the SPA for a non-admin account (no JSON 404)')

  // --- 7. /feedback direct open: same SPA-fallback assertion as /admin,
  // plus the feedback view itself must render for a normal account.
  const fresp = await page.goto(BASE + '/feedback', { waitUntil: 'networkidle' })
  if (!fresp || fresp.status() !== 200) fail(`GET /feedback returned ${fresp && fresp.status()}, expected 200`)
  const ftext = await page.locator('body').innerText()
  if (ftext.includes('接口不存在') || ftext.trim().startsWith('{')) fail('/feedback shows a JSON error instead of the SPA (SPA fallback list missing /feedback)')
  if (!ftext.includes('意见反馈')) fail('/feedback did not render the feedback view')
  await page.screenshot({ path: `${SHOTS}/05-feedback.png` })
  step('/feedback renders on direct open')
  console.log('[e2e] ALL PASSED')
} catch (e) {
  console.error('[e2e] FAILED:', e.message)
  try { await page.screenshot({ path: `${SHOTS}/99-failure.png` }) } catch {}
  process.exitCode = 1
} finally {
  await browser.close()
}
