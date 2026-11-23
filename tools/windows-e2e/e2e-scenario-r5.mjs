// Scenario R5 (1.1.0 修订五): 状态×进度联动 + 浮层几何, against the REAL
// backend (no proxy, no mock). Mirrors e2e-scenario-e/f structure.
// 测试数据全为虚构中性示例（示例事项甲/乙、示例任务与故事）。
//
// Covered flows:
//   F 进度全路径: 0→50(带阻塞)→100→暂不(卡片出现「可提交验收」标记)
//     →降 90→再 100(提示重现)→提交验收(自动补满+自动清阻塞, 历史 UNBLOCK)
//     →组长打回(已退回标记+退回意见, 进度保持 100)→再提交→通过 DONE
//   G 待开始拖动进度: 滑杆松手自动开始 + 顶部提示条 [撤销] 5 秒内撤销
//     回到待开始且进度 0; 超时后不再可撤销
//   H 任务故事全完成: 任务推算待验收 → 任务详情页就地提示 → 验收任务 DONE
//   浮层几何: ItemDrawer 状态下拉打开后断言菜单外框完整在视口内,
//     且每个菜单项中心 elementFromPoint 命中自身 (未被裁切/遮挡)
//
// Env: IB_BASE (default http://localhost:18080), IB_SHOTS (default ./shots)
import { chromium } from 'playwright'
import { mkdirSync } from 'node:fs'

const BASE = (process.env.IB_BASE || 'http://localhost:18080').replace(/\/$/, '')
const SHOTS = process.env.IB_SHOTS || './shots'
mkdirSync(SHOTS, { recursive: true })
const TZ = 'Asia/Shanghai'

function step(m) { console.log(`[scenario-r5] ${m}`) }
function fail(m) { throw new Error(m) }
function assert(cond, m) { if (!cond) fail(m) }

const USERS = {
  leader: { login: 'r5_lead', name: '示例组长', password: 'R5Test1234@' },
  po: { login: 'r5_po', name: '示例PO', password: 'R5Test1234@' },
  m1: { login: 'r5_m1', name: '示例成员', password: 'R5Test1234@' },
}
const TEAM = { name: '修订五示例组', code: 'RFT' }

// 1.1.0 first-run overlays: close whichever is visible; the close button
// must be the TOPMOST one (.last()) - verified pitfall from scenario-e/f.
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

async function transition(ctx, teamId, itemId, action, extra) {
  const fresh = await api(ctx, 'GET', `/teams/${teamId}/items/${itemId}`)
  return api(ctx, 'POST', `/teams/${teamId}/items/${itemId}/transitions`, { action, version: fresh.version, ...(extra ?? {}) })
}
async function getItem(ctx, teamId, itemId) { return api(ctx, 'GET', `/teams/${teamId}/items/${itemId}`) }

// Open an item's drawer from the board by its title.
async function openDrawer(page, title) {
  await page.goto(BASE + '/board', { waitUntil: 'load' })
  await page.waitForSelector('aside.sidebar', { timeout: 15000 })
  await dismissOverlays(page)
  const card = page.locator('.kcard', { hasText: title }).first()
  await card.waitFor({ state: 'visible', timeout: 15000 })
  await card.click()
  await page.waitForSelector('aside.drawer', { timeout: 10000 })
  await page.waitForTimeout(500)
}
async function closeDrawer(page) {
  await page.locator('aside.drawer button[aria-label="关闭"]').click()
  await page.waitForSelector('aside.drawer', { state: 'detached', timeout: 8000 }).catch(() => {})
}

// Set the drawer progress slider; fireChange=true also dispatches `change`
// (slider release -> auto-start flow for NOT_STARTED items).
async function setSlider(page, v, fireChange) {
  const slider = page.locator('aside.drawer input[type=range]')
  await slider.waitFor({ state: 'visible', timeout: 10000 })
  await slider.evaluate((el, val) => {
    el.value = String(val)
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }, v)
  if (fireChange) await slider.evaluate((el) => el.dispatchEvent(new Event('change', { bubbles: true })))
}

async function submitProgress(page, text) {
  const ta = page.locator('aside.drawer .dfoot textarea').first()
  await ta.fill(text)
  await page.locator('aside.drawer .dfoot button', { hasText: '提交' }).last().click()
  await page.waitForTimeout(900)
}

// Overlay geometry: panel fully inside viewport (>=8px margins, 1px
// tolerance) and every item center hit-tests to itself.
async function assertOverlayGeometry(page, label) {
  const panel = page.locator('.ovl').last()
  await panel.waitFor({ state: 'visible', timeout: 8000 })
  const box = await panel.boundingBox()
  const vp = page.viewportSize()
  assert(box, `${label}: overlay has no bounding box`)
  assert(box.x >= 7.5 && box.y >= 7.5 && box.x + box.width <= vp.width - 7.5 && box.y + box.height <= vp.height - 7.5,
    `${label}: overlay box ${JSON.stringify(box)} escapes viewport ${vp.width}x${vp.height}`)
  const items = panel.locator('.ovl-item')
  const n = await items.count()
  assert(n > 0, `${label}: overlay has no items`)
  for (let i = 0; i < n; i++) {
    const it = items.nth(i)
    const hit = await it.evaluate((el) => {
      const r = el.getBoundingClientRect()
      const top = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
      return top === el || el.contains(top)
    })
    assert(hit, `${label}: menu item #${i} center is covered/clipped (elementFromPoint miss)`)
  }
  step(`${label}: overlay geometry OK (${n} items, all hit-tested)`)
}

const browser = await chromium.launch()
try {
  const mk = async () => {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, timezoneId: TZ })
    const page = await ctx.newPage()
    return { ctx, page }
  }
  const L = await mk(), P = await mk(), A = await mk()

  // --- setup: 3 accounts, one team, PO role ------------------------------
  await registerViaUi(L.page, USERS.leader)
  const team = await api(L.ctx, 'POST', '/teams', { name: TEAM.name, code: TEAM.code, joinApproval: false })
  const teamId = team.id
  if (!team.inviteCode) fail('team DTO has no inviteCode')
  for (const [who, sess] of [['po', P], ['m1', A]]) {
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
  const ids = { leader: idOf('r5_lead'), po: idOf('r5_po'), m1: idOf('r5_m1') }
  if (!ids.po || !ids.m1) fail(`member ids unresolved: ${JSON.stringify(ids)}`)
  await api(L.ctx, 'PATCH', `/teams/${teamId}/members/${ids.po}/role`, { role: 'PO' })
  step('setup complete: leader/PO/member in one team')

  // ================= Scenario F: progress full path =====================
  const itemA = await api(L.ctx, 'POST', `/teams/${teamId}/items`, {
    type: 'ADHOC', title: 'R5 示例事项甲', destination: 'ASSIGN', ownerIds: [ids.m1], priority: 'P2',
  })
  await openDrawer(A.page, 'R5 示例事项甲')
  // 规则 6 附带断言: 未估算不阻拦, 进行中后卡片/抽屉给「未估算」标记 (drawer prog-sec)
  await setSlider(A.page, 50, false)
  // 50% 并标记阻塞 (为后面清阻塞断言埋点)
  await A.page.locator('aside.drawer .dfoot label.small input[type=checkbox]').check()
  await A.page.locator('aside.drawer .dfoot input[placeholder="阻塞原因"]').fill('等示例资料')
  await submitProgress(A.page, '已完成一半内容')
  let cur = await getItem(A.ctx, teamId, itemA.id)
  assert(cur.status === 'IN_PROGRESS' && cur.progressPercent === 50 && cur.blocked === true,
    `F: after 50% expected IN_PROGRESS/50/blocked, got ${cur.status}/${cur.progressPercent}/${cur.blocked}`)
  step('F: 0->50 auto-started, blocked set')
  assert(await A.page.locator('aside.drawer .prog-sec .mark.amber', { hasText: '未估算' }).count() >= 1,
    'F/规则6: 未估算标记未出现在抽屉进度区')

  await setSlider(A.page, 100, false)
  await submitProgress(A.page, '已完成全部内容')
  cur = await getItem(A.ctx, teamId, itemA.id)
  assert(cur.status === 'IN_PROGRESS' && cur.progressPercent === 100, `F: after 100 expected IN_PROGRESS/100, got ${cur.status}/${cur.progressPercent}`)
  const prompt = A.page.locator('[data-testid=progress-prompt]')
  await prompt.waitFor({ state: 'visible', timeout: 8000 })
  assert(await prompt.locator('button', { hasText: '提交验收' }).count() >= 1, 'F: prompt main action should be 提交验收 (acceptor is leader)')
  step('F: 100% prompt shown (no auto state change)')

  // 暂不 -> 标记
  await prompt.locator('button', { hasText: '暂不' }).click()
  await A.page.waitForTimeout(300)
  assert(await A.page.locator('[data-testid=progress-prompt]').count() === 0, 'F: prompt should hide after 暂不')
  assert(await A.page.locator('aside.drawer .prog-sec .mark.green', { hasText: '可提交验收' }).count() >= 1, 'F: 可提交验收标记未出现在抽屉')
  await closeDrawer(A.page)
  const cardA = A.page.locator('.kcard', { hasText: 'R5 示例事项甲' }).first()
  assert((await cardA.innerText()).includes('可提交验收'), 'F: 看板卡片未显示「可提交验收」标记')
  step('F: 暂不 -> card/drawer show 可提交验收 mark')
  await A.page.screenshot({ path: `${SHOTS}/R5-F-ready-mark.png` })

  // 降 90 -> 再 100: 提示重新出现
  await openDrawer(A.page, 'R5 示例事项甲')
  await setSlider(A.page, 90, false)
  await submitProgress(A.page, '发现一处问题，回调到 90')
  cur = await getItem(A.ctx, teamId, itemA.id)
  assert(cur.progressPercent === 90, `F: expected 90, got ${cur.progressPercent}`)
  assert(await A.page.locator('[data-testid=progress-prompt]').count() === 0, 'F: prompt must stay hidden at 90')
  await setSlider(A.page, 100, false)
  await submitProgress(A.page, '问题已修复，再次完成')
  await A.page.locator('[data-testid=progress-prompt]').waitFor({ state: 'visible', timeout: 8000 })
  step('F: 90 -> 100 prompt reappears')

  // 提交验收 (主按钮): 状态待验收, 阻塞自动清除 + 历史 UNBLOCK
  await A.page.locator('[data-testid=progress-prompt] button', { hasText: '提交验收' }).click()
  await A.page.waitForTimeout(900)
  cur = await getItem(A.ctx, teamId, itemA.id)
  assert(cur.status === 'IN_REVIEW' && cur.progressPercent === 100 && cur.blocked === false,
    `F: after submit expected IN_REVIEW/100/unblocked, got ${cur.status}/${cur.progressPercent}/${cur.blocked}`)
  const acts = await api(A.ctx, 'GET', `/teams/${teamId}/items/${itemA.id}/activities`)
  assert(acts.some((a) => a.action === 'UNBLOCK'), 'F: activities missing UNBLOCK trace for auto-clear')
  step('F: submitted, blocked auto-cleared with UNBLOCK history')
  await closeDrawer(A.page)

  // 组长打回: 先断言状态下拉浮层几何, 再点打回填原因
  await openDrawer(L.page, 'R5 示例事项甲')
  await L.page.locator('aside.drawer .pillbtn').click()
  await assertOverlayGeometry(L.page, 'F: ItemDrawer status menu')
  await L.page.screenshot({ path: `${SHOTS}/R5-F-status-menu.png` })
  await L.page.locator('.ovl .ovl-item', { hasText: '打回' }).click()
  await L.page.waitForTimeout(500)
  // 原因弹窗是最上层 Modal: 填原因并确认 (弹窗关闭/确认取最上层按钮)
  await L.page.locator('.modal textarea').last().fill('示例退回意见')
  await L.page.locator('.modal button', { hasText: '确认' }).last().click()
  await L.page.waitForTimeout(900)
  cur = await getItem(L.ctx, teamId, itemA.id)
  assert(cur.status === 'IN_PROGRESS' && cur.progressPercent === 100 && cur.rejected === true && cur.rejectReason === '示例退回意见',
    `F: after reject expected IN_PROGRESS/100/rejected, got ${cur.status}/${cur.progressPercent}/${cur.rejected}/${cur.rejectReason}`)
  step('F: rejected, progress kept at 100, marker + reason derived')
  await closeDrawer(L.page)

  // 成员侧: 已退回标记 + 意见可见; 再次提交 (提示重现后点提交验收)
  await openDrawer(A.page, 'R5 示例事项甲')
  assert(await A.page.locator('aside.drawer .prog-sec .mark.red', { hasText: '已退回' }).count() >= 1, 'F: 已退回标记未出现在抽屉')
  assert((await A.page.locator('aside.drawer .prog-sec').innerText()).includes('示例退回意见'), 'F: 退回意见未显示')
  await A.page.locator('[data-testid=progress-prompt] button', { hasText: '提交验收' }).click()
  await A.page.waitForTimeout(900)
  cur = await getItem(A.ctx, teamId, itemA.id)
  assert(cur.status === 'IN_REVIEW' && cur.rejected === false, `F: resubmit expected IN_REVIEW & marker cleared, got ${cur.status}/${cur.rejected}`)
  await closeDrawer(A.page)

  // 组长通过 -> DONE
  await openDrawer(L.page, 'R5 示例事项甲')
  await L.page.locator('aside.drawer .pillbtn').click()
  await L.page.locator('.ovl .ovl-item', { hasText: '通过' }).click()
  await L.page.waitForTimeout(900)
  cur = await getItem(L.ctx, teamId, itemA.id)
  assert(cur.status === 'DONE' && cur.progressPercent === 100, `F: approve expected DONE/100, got ${cur.status}/${cur.progressPercent}`)
  step('F: approved -> DONE (full path passed)')
  await closeDrawer(L.page)

  // ================= Scenario G: drag auto-start + undo =================
  const itemB = await api(L.ctx, 'POST', `/teams/${teamId}/items`, {
    type: 'ADHOC', title: 'R5 示例事项乙', destination: 'ASSIGN', ownerIds: [ids.m1], priority: 'P2',
  })
  await openDrawer(A.page, 'R5 示例事项乙')
  await setSlider(A.page, 30, true) // 松手触发自动开始
  await A.page.waitForTimeout(900)
  cur = await getItem(A.ctx, teamId, itemB.id)
  assert(cur.status === 'IN_PROGRESS' && cur.progressPercent === 30, `G: drag expected IN_PROGRESS/30, got ${cur.status}/${cur.progressPercent}`)
  const undoBtn = A.page.locator('.toast button.tact', { hasText: '撤销' })
  await undoBtn.waitFor({ state: 'visible', timeout: 5000 })
  step('G: auto-started, undo toast visible')
  await undoBtn.click()
  await A.page.waitForTimeout(900)
  cur = await getItem(A.ctx, teamId, itemB.id)
  assert(cur.status === 'NOT_STARTED' && cur.progressPercent === 0, `G: undo expected NOT_STARTED/0, got ${cur.status}/${cur.progressPercent}`)
  step('G: undo reverted to NOT_STARTED with progress 0')
  // 再次拖动开始, 等过 5 秒: 撤销入口消失且状态保持进行中
  await setSlider(A.page, 30, true)
  await A.page.waitForTimeout(900)
  assert((await getItem(A.ctx, teamId, itemB.id)).status === 'IN_PROGRESS', 'G: second drag should auto-start again')
  await A.page.waitForTimeout(5600)
  assert(await A.page.locator('.toast button.tact', { hasText: '撤销' }).count() === 0, 'G: undo entry must expire after 5s')
  assert((await getItem(A.ctx, teamId, itemB.id)).status === 'IN_PROGRESS', 'G: status must stay IN_PROGRESS after undo window')
  step('G: undo window expires, item stays IN_PROGRESS')
  await closeDrawer(A.page)

  // ================= Scenario H: task stories all done ==================
  const task = await api(P.ctx, 'POST', `/teams/${teamId}/items`, { type: 'TASK', title: 'R5 示例任务', destination: 'POOL' })
  const s1 = await api(P.ctx, 'POST', `/teams/${teamId}/items`, { type: 'STORY', title: 'R5 示例故事一', destination: 'POOL', parentId: task.id, storyCategory: 'DEV', estimate: 3 })
  const s2 = await api(P.ctx, 'POST', `/teams/${teamId}/items`, { type: 'STORY', title: 'R5 示例故事二', destination: 'POOL', parentId: task.id, storyCategory: 'TEST', estimate: 1 })
  for (const s of [s1, s2]) {
    await transition(A.ctx, teamId, s.id, 'CLAIM')
    await api(A.ctx, 'POST', `/teams/${teamId}/items/${s.id}/progress`, { text: '故事已完成', percent: 100 })
    await transition(A.ctx, teamId, s.id, 'SUBMIT')
    const st = await getItem(A.ctx, teamId, s.id)
    if (st.status === 'IN_REVIEW') await transition(P.ctx, teamId, s.id, 'APPROVE')
    const done = await getItem(A.ctx, teamId, s.id)
    assert(done.status === 'DONE', `H: story ${s.id} expected DONE, got ${done.status}`)
  }
  let t = await getItem(P.ctx, teamId, task.id)
  assert(t.taskStatus === 'PENDING_ACCEPTANCE' && t.progressPercent === 100, `H: task expected PENDING_ACCEPTANCE/100, got ${t.taskStatus}/${t.progressPercent}`)
  step('H: task derived PENDING_ACCEPTANCE, weighted progress 100')
  await P.page.goto(BASE + `/task/${task.id}`, { waitUntil: 'load' })
  await P.page.waitForSelector('aside.sidebar', { timeout: 15000 })
  await dismissOverlays(P.page)
  const tp = P.page.locator('[data-testid=task-prompt]')
  await tp.waitFor({ state: 'visible', timeout: 10000 })
  assert((await tp.innerText()).includes('故事已全部完成'), 'H: task prompt text mismatch')
  await P.page.screenshot({ path: `${SHOTS}/R5-H-task-prompt.png` })
  await tp.locator('button', { hasText: '验收任务' }).click()
  await P.page.waitForTimeout(900)
  t = await getItem(P.ctx, teamId, task.id)
  assert(t.taskStatus === 'DONE', `H: after accept expected task DONE, got ${t.taskStatus}`)
  step('H: leader/PO accepted task from inline prompt -> DONE')

  console.log('[scenario-r5] ALL PASSED')
} finally {
  await browser.close()
}
