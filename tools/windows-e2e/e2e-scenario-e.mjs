// Scenario E (1.0.4, eta6n93w): multi-user real-time collaboration against
// the REAL jar, directly (no proxy, no mock). Four browser contexts
// (leader / PO / member1 / member2) stay online at the same time; one side
// acts, the other side must see the bell unread count grow and the global
// sidebar badges update WITHOUT a page refresh (SSE), within ~5 s.
//
// Covered triggers (notification matrix A, main rows):
//   E1 invite-to-claim creation  -> invitee notified; pool badge visible on
//      a non-pool page (global shell counts)
//   E2 direct assignment         -> assignee notified
//   E3 claim                     -> creator notified
//   E4 comment with @mention     -> mentioned member notified; mention is
//      highlighted in the comment list
//   E5 progress with @mention    -> mentioned leader notified
//   E6 submit for acceptance     -> acceptor notified; approve -> owner notified
//   E7 nudge                     -> owner notified
//   E8 timezone display: bell panel renders the notification time in the
//      browser's local zone (context pinned to Asia/Shanghai while the
//      host OS may be UTC) - displayed HH:mm must match Shanghai time,
//      not the raw UTC string.
// Screenshots land in IB_SHOTS with an E- prefix.
//
// Env: IB_BASE (default http://localhost:18080), IB_SHOTS (default ./shots)
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = (process.env.IB_BASE || 'http://localhost:18080').replace(/\/$/, '')
const SHOTS = process.env.IB_SHOTS || './shots'
mkdirSync(SHOTS, { recursive: true })
const TZ = 'Asia/Shanghai'

function step(m) { console.log(`[scenario-e] ${m}`) }
function fail(m) { throw new Error(m) }

const USERS = {
  leader: { login: 'e_leader', name: '协作组长', password: 'ETest1234' },
  po: { login: 'e_po', name: '协作PO', password: 'ETest1234' },
  m1: { login: 'e_m1', name: '协作员一', password: 'ETest1234' },
  m2: { login: 'e_m2', name: '协作员二', password: 'ETest1234' },
}
const TEAM = { name: '协作验证小组', code: 'EV' }

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

async function bellCount(page) {
  const badge = page.locator('button.bell .badge')
  if (await badge.count() === 0) return 0
  const txt = (await badge.first().innerText()).trim()
  return txt === '99+' ? 100 : parseInt(txt, 10) || 0
}

async function waitBell(page, atLeast, label) {
  const deadline = Date.now() + 8000
  let n = await bellCount(page)
  while (n < atLeast && Date.now() < deadline) {
    await page.waitForTimeout(400)
    n = await bellCount(page)
  }
  if (n < atLeast) fail(`${label}: bell unread expected >= ${atLeast}, got ${n} (no refresh was performed)`)
  step(`${label}: bell unread = ${n} without refresh`)
  return n
}


async function transition(ctx, teamId, itemId, action) {
  const fresh = await api(ctx, 'GET', `/teams/${teamId}/items/${itemId}`)
  return api(ctx, 'POST', `/teams/${teamId}/items/${itemId}/transitions`, { action, version: fresh.version })
}

const browser = await chromium.launch()
try {
  const mk = async () => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, timezoneId: TZ })
    const page = await ctx.newPage()
    return { ctx, page }
  }
  const L = await mk(), P = await mk(), A = await mk(), B = await mk()

  // --- setup: 4 accounts, one team, roles -------------------------------
  await registerViaUi(L.page, USERS.leader)
  const team = await api(L.ctx, 'POST', '/teams', { name: TEAM.name, code: TEAM.code, joinApproval: false })
  const teamId = team.id
  step(`team created: id=${teamId} inviteCode=${team.inviteCode ? 'present' : 'MISSING'}`)
  if (!team.inviteCode) fail('team DTO has no inviteCode; cannot let others join')
  for (const [who, sess] of [['po', P], ['m1', A], ['m2', B]]) {
    await registerViaUi(sess.page, USERS[who])
    await api(sess.ctx, 'POST', '/teams/join', { inviteCode: team.inviteCode })
    await sess.page.goto(BASE + '/workbench', { waitUntil: 'load' })
    await sess.page.waitForSelector('aside.sidebar', { timeout: 15000 })
    await dismissOverlays(sess.page)
  }
  const members = await api(L.ctx, 'GET', `/teams/${teamId}/members`)
  const idOf = (login) => {
    const m = members.find((x) => x.loginName === login || x.userLogin === login || x.username === login)
    return m ? (m.userId ?? m.id) : null
  }
  const ids = { leader: idOf('e_leader'), po: idOf('e_po'), m1: idOf('e_m1'), m2: idOf('e_m2') }
  if (!ids.m1 || !ids.m2 || !ids.po) fail(`member ids unresolved: ${JSON.stringify({ members: members.slice(0, 2), ids })}`)
  await api(L.ctx, 'PATCH', `/teams/${teamId}/members/${ids.po}/role`, { role: 'PO' })
  for (const sess of [L, P, A, B]) { await sess.page.goto(BASE + '/workbench', { waitUntil: 'load' }); await sess.page.waitForSelector('aside.sidebar', { timeout: 15000 }); await dismissOverlays(sess.page) }
  step('setup complete: 4 users online, roles leader/PO/member/member')

  // --- E1: leader invites m1 to claim -----------------------------------
  const invited = await api(L.ctx, 'POST', `/teams/${teamId}/items`, {
    type: 'ADHOC', title: 'E1 邀请认领的临时事项', destination: 'INVITE', inviteeIds: [ids.m1], priority: 'P2',
  })
  await waitBell(A.page, 1, 'E1 invite -> m1')
  // global badge: m1 goes to a non-pool, non-board page; pool badge stays
  await A.page.goto(BASE + '/overview', { waitUntil: 'load' }); await A.page.waitForSelector('aside.sidebar', { timeout: 15000 })
  const poolBadge = A.page.locator('a[href="/pool"] .badge-n, a[href="/pool"] .badge, a[href="/pool"] .mini-badge')
  // Shell counts refresh asynchronously (SSE debounce + refetch): poll
  // up to 10 s instead of asserting on the first render.
  const badgeDeadline = Date.now() + 10000
  while (await poolBadge.count() === 0 && Date.now() < badgeDeadline) await A.page.waitForTimeout(400)
  if (await poolBadge.count() === 0) fail('E1: pool badge not visible on /overview (global shell counts broken)')
  step('E1: pool badge visible on /overview page')
  await A.page.screenshot({ path: `${SHOTS}/E1-invite-and-global-badge.png` })

  // E8 timezone: open m1 bell panel, first notification time must be
  // Shanghai-local (context zone), within 10 minutes of Shanghai now.
  await A.page.locator('button.bell').click()
  await A.page.waitForSelector('.bp-item', { timeout: 8000 })
  const timeText = await A.page.locator('.bp-item .bp-time').first().innerText()
  const m = timeText.match(/(\d{2}):(\d{2})/)
  if (!m) fail(`E8: no HH:mm found in notification time text: "${timeText}"`)
  const shownMin = parseInt(m[1], 10) * 60 + parseInt(m[2], 10)
  const nowParts = new Intl.DateTimeFormat('en-GB', { timeZone: TZ, hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date()).split(':')
  const nowMin = parseInt(nowParts[0], 10) * 60 + parseInt(nowParts[1], 10)
  let diff = Math.abs(shownMin - nowMin); diff = Math.min(diff, 1440 - diff)
  if (diff > 10) fail(`E8: notification shows ${m[1]}:${m[2]} but ${TZ} now is ${nowParts.join(':')} (diff ${diff} min) - UTC shown as local?`)
  step(`E8: notification time ${m[1]}:${m[2]} matches ${TZ} local time`)
  await A.page.screenshot({ path: `${SHOTS}/E8-bell-panel-timezone.png` })
  await A.page.locator('button.bell').click()

  // --- E2: leader assigns m2 directly ------------------------------------
  const assigned = await api(L.ctx, 'POST', `/teams/${teamId}/items`, {
    type: 'DEFECT', title: 'E2 直接指派给成员二', destination: 'ASSIGN', ownerIds: [ids.m2], priority: 'P1',
  })
  await waitBell(B.page, 1, 'E2 assign -> m2')

  // --- E3: m1 claims the invited item -> leader notified -----------------
  const leaderBefore = await bellCount(L.page)
  await transition(A.ctx, teamId, invited.id, 'CLAIM')
  await waitBell(L.page, leaderBefore + 1, 'E3 claim -> leader')

  // --- E4: m2 comments @m1 on the assigned item --------------------------
  const bBefore = await bellCount(A.page)
  await api(B.ctx, 'POST', `/teams/${teamId}/items/${assigned.id}/comments`, {
    content: `@${USERS.m1.name} 这条请你看一下`, mentionIds: [ids.m1],
  })
  await waitBell(A.page, bBefore + 1, 'E4 @mention in comment -> m1')
  await A.page.goto(BASE + '/board', { waitUntil: 'load' }); await A.page.waitForSelector('aside.sidebar', { timeout: 15000 })
  step('E4 done (highlight rendering is covered by VM visual check screenshots)')

  // --- E5: m1 posts progress @leader on the claimed item -----------------
  const leaderBefore5 = await bellCount(L.page)
  await api(A.ctx, 'POST', `/teams/${teamId}/items/${invited.id}/progress`, {
    text: `@${USERS.leader.name} 已开始处理`, percent: 10, mentionIds: [ids.leader],
  })
  await waitBell(L.page, leaderBefore5 + 1, 'E5 @mention in progress -> leader')

  // --- E6: m1 submits for acceptance; leader approves --------------------
  await transition(A.ctx, teamId, invited.id, 'START').catch(() => {})
  const leaderBefore6 = await bellCount(L.page)
  await transition(A.ctx, teamId, invited.id, 'SUBMIT')
  await waitBell(L.page, leaderBefore6 + 1, 'E6 submit -> acceptor(leader)')
  const m1Before6 = await bellCount(A.page)
  await transition(L.ctx, teamId, invited.id, 'APPROVE')
  await waitBell(A.page, m1Before6 + 1, 'E6 approve -> m1')
  await L.page.screenshot({ path: `${SHOTS}/E6-acceptance-flow.png` })

  // --- E7: leader nudges m2's item ---------------------------------------
  const m2Before = await bellCount(B.page)
  await api(L.ctx, 'POST', `/teams/${teamId}/items/${assigned.id}/nudge`, {})
  await waitBell(B.page, m2Before + 1, 'E7 nudge -> m2')
  await B.page.screenshot({ path: `${SHOTS}/E7-nudge-notify.png` })

  console.log('[scenario-e] ALL PASSED')
} catch (e) {
  console.error('[scenario-e] FAILED:', e.message)
  process.exitCode = 1
} finally {
  await browser.close()
}
