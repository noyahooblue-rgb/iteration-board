// Chrome 128 compatibility gate (wb89pp2z) — replaces the old
// `chrome --dump-dom` smoke check, which could snapshot the page before
// the Vue SPA had mounted and could not see script errors at all.
//
// Drives a REAL browser via Playwright:
//   - IB_CHROME_EXE set  -> Chrome for Testing 128.0.6613.x binary
//     (the compatibility gate proper: the browser our users run).
//   - IB_CHROME_EXE empty -> Playwright's bundled Chromium (129),
//     the control group. 129 green + 128 red with a pageerror means a
//     real 128 compatibility bug; both flaky with no errors means a
//     timing problem instead.
//
// Pass criteria (all must hold):
//   1. <html data-ib-ready="1"> appears within 30 s (root component
//      mounted — set in frontend/src/App.vue onMounted).
//   2. [data-testid="login-form"] appears within 30 s.
//   3. No pageerror events at all.
//   4. No console "error" messages except the whitelist below.
//
// Evidence (always written to IB_EVIDENCE): <label>.png screenshot,
// <label>.html final page content, <label>-report.json with console /
// pageerror / failed-request details, so a red run is diagnosable
// from the artifact alone.
//
// Env: IB_BASE (default http://localhost:18080), IB_CHROME_EXE,
//      IB_EVIDENCE (default ./chrome128-evidence), IB_LABEL
import { chromium } from 'playwright'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = (process.env.IB_BASE || 'http://localhost:18080').replace(/\/$/, '')
const EXE = process.env.IB_CHROME_EXE || ''
const LABEL = process.env.IB_LABEL || (EXE ? 'chrome128' : 'chromium129')
const EVIDENCE = process.env.IB_EVIDENCE || './chrome128-evidence'
mkdirSync(EVIDENCE, { recursive: true })

// Known-harmless console errors (each entry justified):
// - "Failed to load resource ... 401" for /api/v1/auth/* probes made
//   by the router guard / auth store before anyone is logged in.
//   Being unauthenticated on the login page is the expected state.
const CONSOLE_ERROR_WHITELIST = [
  /Failed to load resource: the server responded with a status of 401/,
]

const consoleMsgs = []
const pageErrors = []
const failedRequests = []
const badResponses = []

function isWhitelisted(text) {
  return CONSOLE_ERROR_WHITELIST.some((re) => re.test(text))
}

let browser
let readyOk = false
let formOk = false
let version = ''
try {
  browser = await chromium.launch(EXE ? { executablePath: EXE } : {})
  version = browser.version()
  console.log(`[s5] label=${LABEL} browser version=${version} exe=${EXE || '(bundled)'}`)
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  page.on('console', (m) => consoleMsgs.push({ type: m.type(), text: m.text() }))
  page.on('pageerror', (e) => pageErrors.push({ message: String(e && e.message || e), stack: String((e && e.stack) || '') }))
  page.on('requestfailed', (r) => failedRequests.push({ url: r.url(), error: (r.failure() && r.failure().errorText) || '' }))
  page.on('response', (r) => { if (r.status() >= 400) badResponses.push({ url: r.url(), status: r.status() }) })

  const resp = await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 60000 })
  console.log(`[s5] GET / -> ${resp && resp.status()}`)
  try {
    await page.waitForSelector('html[data-ib-ready="1"]', { timeout: 30000 })
    readyOk = true
  } catch { console.log('[s5] TIMEOUT waiting for html[data-ib-ready="1"]') }
  try {
    await page.waitForSelector('[data-testid="login-form"]', { timeout: 30000 })
    formOk = true
  } catch { console.log('[s5] TIMEOUT waiting for [data-testid="login-form"]') }

  await page.screenshot({ path: join(EVIDENCE, `${LABEL}.png`) }).catch(() => {})
  const content = await page.content().catch(() => '')
  writeFileSync(join(EVIDENCE, `${LABEL}.html`), content)
} catch (e) {
  pageErrors.push({ message: `driver error: ${e && e.message || e}`, stack: String((e && e.stack) || '') })
} finally {
  if (browser) await browser.close().catch(() => {})
}

const realConsoleErrors = consoleMsgs.filter((m) => m.type === 'error' && !isWhitelisted(m.text))
const report = {
  label: LABEL, browserVersion: version, base: BASE,
  readyOk, formOk,
  pageErrors, consoleErrors: realConsoleErrors,
  consoleAll: consoleMsgs, failedRequests, badResponses,
}
writeFileSync(join(EVIDENCE, `${LABEL}-report.json`), JSON.stringify(report, null, 2))

for (const pe of pageErrors) console.log(`[s5] PAGEERROR: ${pe.message}`)
for (const ce of realConsoleErrors) console.log(`[s5] CONSOLE-ERROR: ${ce.text}`)
for (const fr of failedRequests) console.log(`[s5] REQUESTFAILED: ${fr.url} ${fr.error}`)

const pass = readyOk && formOk && pageErrors.length === 0 && realConsoleErrors.length === 0
console.log(`[s5] RESULT ${LABEL}: ${pass ? 'PASS' : 'FAIL'} (ready=${readyOk} loginForm=${formOk} pageErrors=${pageErrors.length} consoleErrors=${realConsoleErrors.length})`)
process.exit(pass ? 0 : 1)
