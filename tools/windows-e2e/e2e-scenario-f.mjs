// Scenario F (1.1.0): feedback flow against the REAL jar, directly
// (no proxy, no mock). Mirrors e2e-scenario-e.mjs structure.
//
// Covered flow:
//   F1 a member submits feedback WITH an image (1x1 PNG, base64) via API
//   F2 another member +1s it
//   F3 the built-in platform admin (login name "admin", password handed
//      over via IB_ADMIN_PW - verify.ps1 phase P1b already changed the
//      initial password) moves it NEW -> ANALYZING -> ACCEPTED with a
//      planned version (the backend state machine has no NEW->ACCEPTED
//      shortcut, so both transitions are asserted)
//   F4 the submitter is notified: the bell unread count grows WITHOUT a
//      refresh (SSE) and the bell panel shows the feedback title; the
//      /notifications API also carries a FEEDBACK_STATUS entry
//   F5 the admin exports the feedback as zip (HTTP 200 + zip
//      content-type + non-trivial body)
//   F6 direct open: /feedback renders for a normal member on a fresh
//      goto AND after a full refresh (SPA fallback, never a JSON 404),
//      and /admin renders the platform-admin view for the real admin
//      (session cookies from the API login are injected into a browser
//      context - the 1.1.0 replacement for e2e.mjs's old "first
//      registered account is admin" assertion, which P01 removed)
// Screenshots land in IB_SHOTS with an F- prefix.
//
// Env: IB_BASE (default http://localhost:18080), IB_SHOTS (default
// ./shots), IB_ADMIN_PW (REQUIRED: admin's current password after the
// P1b forced change in windows-verify.ps1)
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = (process.env.IB_BASE || 'http://localhost:18080').replace(/\/$/, '')
const SHOTS = process.env.IB_SHOTS || './shots'
mkdirSync(SHOTS, { recursive: true })
const ADMIN_PW = process.env.IB_ADMIN_PW || ''
if (!ADMIN_PW) { console.error('[scenario-f] FAILED: IB_ADMIN_PW env is required'); process.exit(1) }

function step(m) { console.log(`[scenario-f] ${m}`) }
function fail(m) { throw new Error(m) }

const USERS = {
  sub: { login: 'f_sub', name: '反馈提交人', password: 'FTest1234' },
  plus: { login: 'f_plus', name: '反馈支持者', password: 'FTest1234' },
}
const FB_TITLE = 'F 剧本反馈标记：看板刷新后希望保留筛选条件'
const PNG_1X1 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8Xw8AAwMCAO+ip1sAAAAASUVORK5CYII='

// 1.1.0 first-run overlays (release-notes modal + onboarding guide)
// intercept clicks until dismissed; close whichever is visible.
async function dismissOverlays(page) {
  for (let i = 0; i < 6; i++) {
    const btn = page.locator('button', { hasText: /^(知道了|跳过引导|知道了，开始使用)$/ }).last()
    if (await btn.count() === 0 || !(await btn.isVisible().catch(() => false))) return
    await btn.click({ timeout: 3000 }).catch(() => {})
    await page.waitForTimeout(400)
  }
}

async function registerViaUi(page, u) {
  await page.goto(BASE + '/', { waitUntil: 'networkidle' })
  await page.waitForSelector('label.field', { timeout: 15000 })
  await page.locator('.seg button', { hasText: '注册' }).click()
  await page.locator('label.field').filter({ hasText: '注册后不可改' }).locator('input').fill(u.login)
  await page.locator('label.field').filter({ hasText: '显示姓名' }).locator('input').fill(u.name)
  await page.locator('label.field').filter({ hasText: '至少 8 位' }).locator('input').fill(u.password)
  const glyphs = await page.locator('button.cap svg text').allTextContents()
  const code = glyphs.join('').trim()
  if (code.length < 4) fail(`captcha glyphs unreadable for ${u.login}: "${code}"`)
  await page.locator('label.field').filter({ hasText: '图形验证码' }).locator('input').fill(code)
  await page.getByRole('button', { name: '注册并登录' }).click()
  await page.waitForURL(/\/(teams\/new|workbench)/, { timeout: 20000 })
}

async function api(ctx, method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (method !== 'GET') {
    // Write calls need the CSRF token the app issues as a cookie
    // (same contract the SPA uses: XSRF-TOKEN cookie -> X-XSRF-TOKEN header).
    const state = await ctx.storageState()
    const csrf = state.cookies.find((c) => c.name === 'XSRF-TOKEN')
    if (csrf) headers['X-XSRF-TOKEN'] = csrf.value
  }
  const res = await ctx.request.fetch(BASE + '/api/v1' + path, {
    method,
    data: body === undefined ? undefined : JSON.stringify(body),
    headers,
  })
  if (!res.ok()) fail(`API ${method} ${path} -> ${res.status()}: ${(await res.text()).slice(0, 300)}`)
  const t = await res.text()
  return t ? JSON.parse(t) : null
}

// --- admin session via a plain cookie jar (no browser needed) --------
// The built-in admin never registers through the UI in this scenario;
// its password was already rotated by verify.ps1 phase P1b. Login needs
// no captcha (captcha is only demanded after 3 failed attempts).
const jar = new Map()
function jarCookies() { return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ') }
function jarAbsorb(res) {
  const set = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : []
  for (const line of set) {
    const pair = line.split(';')[0]
    const eq = pair.indexOf('=')
    if (eq > 0) jar.set(pair.slice(0, eq).trim(), pair.slice(eq + 1).trim())
  }
}
async function adminFetch(method, path, body) {
  const headers = { 'Content-Type': 'application/json', Cookie: jarCookies() }
  if (method !== 'GET' && jar.get('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = jar.get('XSRF-TOKEN')
  const res = await fetch(BASE + '/api/v1' + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  jarAbsorb(res)
  return res
}
async function adminApi(method, path, body) {
  const res = await adminFetch(method, path, body)
  if (!res.ok) fail(`admin API ${method} ${path} -> ${res.status}: ${(await res.text()).slice(0, 300)}`)
  const t = await res.text()
  return t ? JSON.parse(t) : null
}

async function bellCount(page) {
  const badge = page.locator('button.bell .badge')
  if (await badge.count() === 0) return 0
  const txt = (await badge.first().innerText()).trim()
  return txt === '99+' ? 100 : parseInt(txt, 10) || 0
}

async function waitBell(page, atLeast, label) {
  const deadline = Date.now() + 10000
  let n = await bellCount(page)
  while (n < atLeast && Date.now() < deadline) {
    await page.waitForTimeout(400)
    n = await bellCount(page)
  }
  if (n < atLeast) fail(`${label}: bell unread expected >= ${atLeast}, got ${n} (no refresh was performed)`)
  step(`${label}: bell unread = ${n} without refresh`)
  return n
}

const browser = await chromium.launch()
try {
  const mk = async () => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const page = await ctx.newPage()
    return { ctx, page }
  }
  const S = await mk(), P = await mk()
  await registerViaUi(S.page, USERS.sub)
  await registerViaUi(P.page, USERS.plus)
  // Both users need a team: team-scoped routes (/feedback included) are
  // guarded to /teams/new for teamless users. S creates one, P joins.
  const fteam = await api(S.ctx, 'POST', '/teams', { name: '反馈验证小组', code: 'FB', joinApproval: false })
  if (!fteam.inviteCode) fail('feedback team DTO has no inviteCode')
  await api(P.ctx, 'POST', '/teams/join', { inviteCode: fteam.inviteCode })
  for (const sess of [S, P]) {
    await sess.page.goto(BASE + '/workbench', { waitUntil: 'load' })
    await sess.page.waitForSelector('aside.sidebar', { timeout: 15000 })
    await dismissOverlays(sess.page)
  }
  step('setup complete: submitter + supporter registered and teamed')

  // --- admin API login ---------------------------------------------------
  await adminFetch('GET', '/auth/captcha') // seed session + XSRF cookie
  const loginRes = await adminFetch('POST', '/auth/login', { loginName: 'admin', password: ADMIN_PW })
  if (!loginRes.ok) fail(`admin login -> ${loginRes.status}: ${(await loginRes.text()).slice(0, 300)}`)
  const adminMe = await adminApi('GET', '/auth/me')
  if (adminMe.platformAdmin !== true) fail('admin /auth/me platformAdmin is not true')
  if (adminMe.mustChangePassword !== false) fail('admin mustChangePassword still true - P1b change did not land?')
  step('admin API login OK (platformAdmin, password already rotated)')

  // --- F1: submitter posts feedback with an image ------------------------
  const fb = await api(S.ctx, 'POST', '/feedback', {
    type: 'SUGGESTION',
    title: FB_TITLE,
    description: '剧本 F 自动提交：筛选条件在刷新后丢失，附 1x1 截图一张。',
    pageUrl: '/board',
    images: [{ fileName: 'dot.png', contentType: 'image/png', contentBase64: PNG_1X1 }],
  })
  if (!fb.id) fail('feedback create returned no id')
  if (!Array.isArray(fb.images) || fb.images.length !== 1) fail(`feedback image not stored: ${JSON.stringify(fb.images)}`)
  if (fb.status !== 'NEW') fail(`feedback status expected NEW, got ${fb.status}`)
  step(`F1: feedback #${fb.id} created with 1 image, status NEW`)

  // --- F6a: /feedback direct open shows the new feedback ------------------
  const fresp = await S.page.goto(BASE + '/feedback', { waitUntil: 'networkidle' })
  if (!fresp || fresp.status() !== 200) fail(`GET /feedback returned ${fresp && fresp.status()}, expected 200`)
  let body = await S.page.locator('body').innerText()
  if (body.includes('接口不存在') || body.trim().startsWith('{')) fail('/feedback shows a JSON error instead of the SPA (SPA fallback list missing /feedback)')
  if (!body.includes('意见反馈')) fail('/feedback did not render the feedback view')
  if (!body.includes(FB_TITLE)) fail('/feedback list does not show the submitted feedback')
  const rresp = await S.page.reload({ waitUntil: 'networkidle' })
  if (!rresp || rresp.status() !== 200) fail(`refresh /feedback returned ${rresp && rresp.status()}, expected 200`)
  body = await S.page.locator('body').innerText()
  if (!body.includes(FB_TITLE)) fail('submitted feedback missing on /feedback after refresh')
  await S.page.screenshot({ path: `${SHOTS}/F1-feedback-list.png` })
  step('F6a: /feedback renders on direct open and survives a refresh')

  // --- F2: supporter +1 ----------------------------------------------------
  const bellBefore = await bellCount(S.page)
  const plused = await api(P.ctx, 'POST', `/feedback/${fb.id}/plus-one`)
  if (Number(plused.plusOneCount) !== 1) fail(`plus-one count expected 1, got ${plused.plusOneCount}`)
  step('F2: supporter +1 recorded (plusOneCount=1)')

  // --- F3: admin ANALYZING -> ACCEPTED with planned version ---------------
  const analyzing = await adminApi('PATCH', `/feedback/${fb.id}/status`, { status: 'ANALYZING' })
  if (analyzing.status !== 'ANALYZING') fail(`status after ANALYZING patch: ${analyzing.status}`)
  const accepted = await adminApi('PATCH', `/feedback/${fb.id}/status`, { status: 'ACCEPTED', plannedVersion: '1.2.0' })
  if (accepted.status !== 'ACCEPTED') fail(`status after ACCEPTED patch: ${accepted.status}`)
  if (accepted.plannedVersion !== '1.2.0') fail(`plannedVersion expected 1.2.0, got ${accepted.plannedVersion}`)
  step('F3: admin moved feedback NEW -> ANALYZING -> ACCEPTED (plannedVersion 1.2.0)')

  // --- F4: submitter notified (bell, no refresh) ---------------------------
  await waitBell(S.page, bellBefore + 1, 'F4 status change -> submitter')
  await S.page.locator('button.bell').click()
  await S.page.waitForSelector('.bp-item', { timeout: 8000 })
  const panel = await S.page.locator('.bp-item').allInnerTexts()
  if (!panel.some((t) => t.includes(FB_TITLE))) fail(`bell panel shows no entry for the feedback: ${JSON.stringify(panel).slice(0, 300)}`)
  await S.page.screenshot({ path: `${SHOTS}/F2-bell-notification.png` })
  await S.page.locator('button.bell').click()
  const notifs = await api(S.ctx, 'GET', '/notifications?limit=20')
  if (!JSON.stringify(notifs).includes(FB_TITLE)) fail('/notifications carries no entry mentioning the feedback title')
  step('F4: submitter notified - bell panel and /notifications both carry the feedback')

  // --- F5: admin export zip -------------------------------------------------
  const exp = await adminFetch('POST', '/feedback/export', { ids: [fb.id] })
  if (exp.status !== 200) fail(`export -> ${exp.status}: ${(await exp.text()).slice(0, 300)}`)
  const ctype = exp.headers.get('content-type') || ''
  if (!ctype.includes('zip')) fail(`export content-type expected zip, got "${ctype}"`)
  const bytes = await exp.arrayBuffer()
  if (bytes.byteLength < 100) fail(`export zip suspiciously small: ${bytes.byteLength} bytes`)
  step(`F5: admin export OK (200, ${ctype}, ${bytes.byteLength} bytes)`)

  // --- F6b: /admin direct open for the real admin ---------------------------
  const actx = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  await actx.addCookies([...jar.entries()].map(([name, value]) => ({ name, value, domain: 'localhost', path: '/' })))
  const apage = await actx.newPage()
  const aresp = await apage.goto(BASE + '/admin', { waitUntil: 'load' })
  if (!aresp || aresp.status() !== 200) fail(`GET /admin returned ${aresp && aresp.status()}, expected 200`)
  await apage.waitForTimeout(1200)
  const atext = await apage.locator('body').innerText()
  if (atext.includes('接口不存在') || atext.trim().startsWith('{')) fail('/admin shows a JSON error instead of the SPA (SPA fallback list missing /admin)')
  if (!atext.includes('平台管理')) fail('/admin did not render the platform admin view for the built-in admin')
  await apage.screenshot({ path: `${SHOTS}/F3-admin-view.png` })
  step('F6b: /admin renders the platform admin view for the built-in admin')
  await actx.close()

  console.log('[scenario-f] ALL PASSED')
} catch (e) {
  console.error('[scenario-f] FAILED:', e.message)
  process.exitCode = 1
} finally {
  await browser.close()
}
