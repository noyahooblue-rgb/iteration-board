# ============================================================
# Windows runner end-to-end verification for the offline package.
# Invoked by .github/workflows/windows-install.yml on
# windows-latest (the runner user is a local administrator).
#
# Expects the built package zip at
# C:\ibdl\iteration-board-offline-<version>.zip (version pinned below;
# the workflow's upload-artifact path must name the same file).
# (downloaded from the build job's artifact by the workflow).
# All console output of every .bat run is kept under
# C:\ibwork\evidence and uploaded as a workflow artifact,
# together with the Playwright screenshots.
#
# Phases:
#   P1  fresh install (no-space path, --no-pause), full assertions
#   P2  Playwright direct-visit flow + screenshots
#   P1b P01 built-in admin flow (1.1.0): initial-password file, forced
#       password change (403 MUST_CHANGE_PASSWORD), grant/revoke
#       platform admin, LAST_PLATFORM_ADMIN guard, password file
#       deleted by the next start.bat. Runs AFTER P2 because it uses
#       the account P2 registered (ci_admin) as the personal account;
#       it does not touch the e_* accounts of scenario E.
#   P2b Scenario E (multi-user real-time collaboration)
#   P2c Scenario F (feedback flow, 1.1.0): submit with image, +1,
#       admin ACCEPTED with planned version, submitter notified,
#       admin zip export, direct-open /feedback and /admin
#   P3  stop -> backup must SKIP with exit 0 while PG is stopped
# -> start
#   P4  backup (auto) produces backup\<ts>\db.dump
#   P5  restore that backup
#   P12 P23 backup status (1.1.0): data\backup-status.json fields,
#       stop records a result and produces a fresh backup, start
#       within 24 h of a success does NOT back up again
#   P13 migration (1.1.0): marker data on A -> migrate-export ->
#       fresh install B -> migrate-import -> marker data verified
#       on B (manifest hashes verified by the import itself)
#   P6  upgrade in place from a second copy of the same package
#   P14 upgrade + rollback (1.1.0): same-version upgrade records a
#       rollback bundle (upgrade-<ts>\upgrade-info.txt), then
#       rollback.bat restores it and the marker account still logs in
#   P7  uninstall keeping data (services gone, pgdata kept)
#   P8  reinstall over the half-installed state (recovery path,
#       recovery path): existing config + pgdata are reused
#   P9  uninstall again
#   P10 install into a path WITH spaces (warning only, continues)
#   P11 install with --autostart: both services AUTO_START
#; P1 asserted DEMAND_START for the default mode
# ============================================================
$ErrorActionPreference = 'Continue'
$PkgVersion = '1.1.0'
$Zip = "C:\ibdl\iteration-board-offline-$PkgVersion.zip"
$PkgDirName = "iteration-board-offline-$PkgVersion"
$Work = 'C:\ibwork'
$Evidence = Join-Path $Work 'evidence'
New-Item -ItemType Directory -Force $Evidence | Out-Null
$script:Failures = @()

function Check([string]$Name, $Ok) {
  # $Ok is deliberately untyped: several call sites pass -match results
  # or cmdlet output that can materialize as an array; a [bool]-typed
  # parameter turned that into a script-killing binding error (1.1.0
  # first runner round). Coerce: arrays count as their -match/filter
  # semantics (non-empty = true).
  if ($Ok -is [System.Array]) { $Ok = $Ok.Count -gt 0 }
  $Ok = [bool]$Ok
  if ($Ok) { Write-Host "  PASS  $Name" }
  else {
    Write-Host "  FAIL  $Name"
    $script:Failures += $Name
  }
}

function Phase([string]$Name) {
  Write-Host "=== $Name ==="
  Write-Host "::notice::PHASE $Name"
}

function Dump-Tail([string]$LogName, [int]$Lines = 12) {
  # Emit the tail of a phase log as ONE ::error:: annotation
  # (annotations are capped per run, so be economical: phase
  # notices + one result line + a couple of log tails only).
  $path = Join-Path $Evidence $LogName
  if (-not (Test-Path $path)) { return }
  $tail = (Get-Content $path -Tail $Lines | Where-Object { $_.Trim() -ne '' }) -join ' // '
  if ($tail.Length -gt 900) { $tail = $tail.Substring($tail.Length - 900) }
  $tail = $tail -replace '%', '%25' -replace "`r", '%0D' -replace "`n", '%0A'
  Write-Host "::error::LOGTAIL $LogName :: $tail"
}

function Run-Bat([string]$Dir, [string]$Bat, [string[]]$BatArgs, [string]$LogName) {
  $log = Join-Path $Evidence $LogName
  "`r`n===== $Bat $($BatArgs -join ' ')  (cwd: $Dir) =====" | Out-File -Append -Encoding utf8 $log
  Push-Location $Dir
  try {
    & cmd.exe /c ".\$Bat $($BatArgs -join ' ') >> `"$log`" 2>&1"
    $code = $LASTEXITCODE
  } finally { Pop-Location }
  Write-Host "[bat] $Bat $($BatArgs -join ' ') -> exit $code  (log: $LogName)"
  return $code
}

function Wait-State([string]$Name, [string]$State, [int]$TimeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    $svc = Get-Service $Name -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq $State) { return $true }
    Start-Sleep -Seconds 2
  }
  return $false
}

function ScQc([string]$Name) {
  $out = (& sc.exe qc $Name | Out-String)
  $out | Out-File -Append -Encoding utf8 (Join-Path $Evidence 'sc-qc.txt')
  return $out
}

function Http-Ok([string]$Url, [int]$TimeoutSec = 60) {
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5
      if ($r.StatusCode -eq 200) { return $true }
    } catch { Start-Sleep -Seconds 3 }
  }
  return $false
}

# --- API helpers (1.1.0 phases P1b/P13/P14) -------------------------
# Session-cookie auth, same contract as the SPA: a GET first seeds the
# session and the XSRF-TOKEN cookie; write calls echo the cookie back
# as the X-XSRF-TOKEN header. Login needs no captcha (the backend only
# demands one after 3 failed attempts), so NEVER retry a failed login
# in a loop - a wrong password three times locks the account.
$script:AdminNewPw = 'CiAdmin2026x'   # P1b rotates the admin password to this
$script:CiAdminPw = 'CiTest1234'      # password of the account P2 registers (e2e.mjs)

function Api-Call($Session, [string]$Method, [string]$Path, $Body) {
  $params = @{
    Uri = "http://localhost:18080/api/v1$Path"; Method = $Method
    WebSession = $Session; UseBasicParsing = $true
    SkipHttpErrorCheck = $true; TimeoutSec = 30
  }
  $headers = @{}
  if ($Method -ne 'Get') {
    $csrf = $Session.Cookies.GetCookies('http://localhost:18080/') |
      Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1
    if ($csrf) { $headers['X-XSRF-TOKEN'] = $csrf.Value }
  }
  if ($null -ne $Body) {
    $params.Body = ($Body | ConvertTo-Json -Depth 6)
    $params.ContentType = 'application/json'
  }
  if ($headers.Count -gt 0) { $params.Headers = $headers }
  $r = Invoke-WebRequest @params
  $parsed = $null
  if ($r.Content) { try { $parsed = $r.Content | ConvertFrom-Json } catch { } }
  return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $parsed; Raw = [string]$r.Content }
}

function Api-Login([string]$User, [string]$Pass) {
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  try {
    Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/auth/captcha' -WebSession $s -TimeoutSec 10 | Out-Null
  } catch { }
  $r = Api-Call $s 'Post' '/auth/login' @{ loginName = $User; password = $Pass }
  if ($r.Status -ne 200) { throw "API login failed for ${User}: HTTP $($r.Status) $($r.Raw)" }
  return $s
}

function Api-Me($Session) {
  $r = Api-Call $Session 'Get' '/auth/me' $null
  if ($r.Status -ne 200) { throw "GET /auth/me -> HTTP $($r.Status)" }
  return $r.Body
}

function Read-AdminInitialPassword([string]$RootPath) {
  $f = Join-Path $RootPath 'config\admin-initial-password.txt'
  if (-not (Test-Path $f)) { return $null }
  $m = Select-String -Path $f -Pattern 'admin initial password:\s*(\S+)' | Select-Object -First 1
  if ($m) { return $m.Matches[0].Groups[1].Value }
  return $null
}

function Assert-Installed([string]$Root, [string]$Phase) {
  Write-Host "[$Phase] asserting installed state at $Root"
  Check "$Phase PG service Running" (Wait-State 'IterationBoardPG' 'Running')
  Check "$Phase App service Running" (Wait-State 'IterationBoardApp' 'Running' 180)
  Check "$Phase postgres.exe process exists" ([bool](Get-Process postgres -ErrorAction SilentlyContinue))
  $listen = Get-NetTCPConnection -LocalPort 15432 -State Listen -ErrorAction SilentlyContinue
  Check "$Phase port 15432 listening" ([bool]$listen)
  & (Join-Path $Root 'runtime\pgsql\bin\pg_isready.exe') -h 127.0.0.1 -p 15432 | Out-Host
  Check "$Phase pg_isready accepts connections" ($LASTEXITCODE -eq 0)
  $pwdLine = Select-String -Path (Join-Path $Root 'config\application.yml') -Pattern '^\s*password:\s*(\S+)' | Select-Object -First 1
  $env:PGPASSWORD = $pwdLine.Matches[0].Groups[1].Value
  $enc = & (Join-Path $Root 'runtime\pgsql\bin\psql.exe') -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -tAc 'SHOW server_encoding'
  $env:PGPASSWORD = $null
  Check "$Phase DB server_encoding is UTF8 (got: $enc)" ($enc -eq 'UTF8')
  Check "$Phase http://localhost:18080/ is 200" (Http-Ok 'http://localhost:18080/')
  Check "$Phase http://localhost:18080/api/v1/meta is 200" (Http-Ok 'http://localhost:18080/api/v1/meta')
}

try {
  if (-not (Test-Path $Zip)) { throw "package zip not found: $Zip" }

  # ---------- P1: fresh install, default (manual start) mode ----------
  Phase 'P1 fresh install (default mode)'
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $Work 'pkg') -Force
  $Root = Join-Path $Work "pkg\$PkgDirName"
  $code = Run-Bat $Root 'install.bat' @('--no-pause') 'p1-install.log'
  Check 'P1 install.bat exit 0' ($code -eq 0)
  $verTxt = Get-Content (Join-Path $Root 'version.txt') -ErrorAction SilentlyContinue | Select-Object -First 1
  Check "P1 package version.txt is $PkgVersion (got: $verTxt)" ($verTxt -eq $PkgVersion)
  Assert-Installed $Root 'P1'
  Check 'P1 PG service START_TYPE is DEMAND_START' ((ScQc 'IterationBoardPG') -match 'DEMAND_START')
  Check 'P1 App service START_TYPE is DEMAND_START' ((ScQc 'IterationBoardApp') -match 'DEMAND_START')

  # ---------- P2: Playwright direct-visit flow ----------
  Phase 'P2 Playwright e2e (direct, no proxy)'
  $e2eLog = Join-Path $Evidence 'p2-playwright.log'
  Push-Location (Join-Path $env:GITHUB_WORKSPACE 'tools\windows-e2e')
  try {
    $env:IB_BASE = 'http://localhost:18080'
    $env:IB_SHOTS = Join-Path $Evidence 'shots'
    & npm install --no-fund --no-audit *>> $e2eLog
    if ($LASTEXITCODE -ne 0) { throw 'npm install failed' }
    & npx playwright install chromium *>> $e2eLog
    if ($LASTEXITCODE -ne 0) { throw 'playwright install failed' }
    & node e2e.mjs *>> $e2eLog
    Check 'P2 Playwright flow passed' ($LASTEXITCODE -eq 0)
  } finally { Pop-Location }
  foreach ($shot in '01-home-login.png', '02-workbench.png', '03-board-after-refresh.png', '04-admin.png', '05-feedback.png') {
    Check "P2 screenshot $shot exists" (Test-Path (Join-Path $Evidence "shots\$shot"))
  }

  # ---------- P1b: P01 built-in admin flow (1.1.0) ----------
  # Uses ci_admin (registered by the P2 Playwright flow) as the
  # personal account: a fresh self-registered account must NOT be a
  # platform admin. The e_* accounts of scenario E do not exist yet
  # at this point and are never touched here.
  Phase 'P1b P01 admin bootstrap flow'
  $AdminPwFile = Join-Path $Root 'config\admin-initial-password.txt'
  Check 'P1b admin-initial-password.txt exists after fresh install' (Test-Path $AdminPwFile)
  $AdminInitPw = Read-AdminInitialPassword $Root
  Check 'P1b initial admin password parsed, length >= 16' ($null -ne $AdminInitPw -and $AdminInitPw.Length -ge 16)
  if ($AdminInitPw) {
    $adminS = Api-Login 'admin' $AdminInitPw
    $meAdmin = Api-Me $adminS
    Check 'P1b admin login: platformAdmin=true' ($meAdmin.platformAdmin -eq $true)
    Check 'P1b admin login: mustChangePassword=true' ($meAdmin.mustChangePassword -eq $true)
    $blocked = Api-Call $adminS 'Get' '/teams/mine' $null
    Check 'P1b business API before password change is 403 MUST_CHANGE_PASSWORD' ($blocked.Status -eq 403 -and $blocked.Raw -match 'MUST_CHANGE_PASSWORD')
    $chg = Api-Call $adminS 'Post' '/auth/change-password' @{ oldPassword = $AdminInitPw; newPassword = $script:AdminNewPw }
    Check 'P1b admin change-password succeeds (204)' ($chg.Status -eq 204)
    $meAdmin2 = Api-Me $adminS
    Check 'P1b mustChangePassword cleared after change' ($meAdmin2.mustChangePassword -eq $false)

    $ciS = Api-Login 'ci_admin' $script:CiAdminPw
    $meCi = Api-Me $ciS
    Check 'P1b newly registered account is NOT platform admin' ($meCi.platformAdmin -eq $false)
    $CiId = $meCi.id
    $grant = Api-Call $adminS 'Patch' "/admin/users/$CiId/platform-admin" @{ platformAdmin = $true }
    Check 'P1b grant platform admin to personal account succeeds' ($grant.Status -eq 200 -and $grant.Body.platformAdmin -eq $true)
    $meCi2 = Api-Me $ciS
    Check 'P1b grant takes effect on the personal account (/auth/me)' ($meCi2.platformAdmin -eq $true)
    # Build the "only one enabled admin left" state by revoking the
    # personal account again (admin stays), then the last-admin guard
    # must refuse to revoke admin itself.
    $revoke = Api-Call $adminS 'Patch' "/admin/users/$CiId/platform-admin" @{ platformAdmin = $false }
    Check 'P1b revoke personal account succeeds while admin remains' ($revoke.Status -eq 200 -and $revoke.Body.platformAdmin -eq $false)
    $lastTry = Api-Call $adminS 'Patch' "/admin/users/$($meAdmin.id)/platform-admin" @{ platformAdmin = $false }
    Check 'P1b revoking the LAST enabled admin is refused (400 LAST_PLATFORM_ADMIN)' ($lastTry.Status -eq 400 -and $lastTry.Raw -match 'LAST_PLATFORM_ADMIN')
    $regrant = Api-Call $adminS 'Patch' "/admin/users/$CiId/platform-admin" @{ platformAdmin = $true }
    Check 'P1b re-grant personal account (leave two admins for later phases)' ($regrant.Status -eq 200)
  }
  # start.bat deletes the initial-password file once the admin
  # password has been changed (it checks must_change_password in DB).
  $code = Run-Bat $Root 'stop.bat' @('--no-pause') 'p1b-stop.log'
  Check 'P1b stop.bat exit 0' ($code -eq 0)
  # stop returns before the JVM has fully exited; an immediate start
  # races the still-bound port / still-closing DB (first 1.1.0 runner
  # round: P1b start failed, P3/P12 starts minutes later passed).
  # Wait until both services report Stopped (max 60 s).
  $settleDeadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $settleDeadline) {
    $appSvc = Get-Service -Name 'IterationBoardApp' -ErrorAction SilentlyContinue
    $pgSvc = Get-Service -Name 'IterationBoardPG' -ErrorAction SilentlyContinue
    if ((-not $appSvc -or $appSvc.Status -eq 'Stopped') -and (-not $pgSvc -or $pgSvc.Status -eq 'Stopped')) { break }
    Start-Sleep -Seconds 3
  }
  $code = Run-Bat $Root 'start.bat' @('--no-pause') 'p1b-start.log'
  Check 'P1b start.bat exit 0' ($code -eq 0)
  Check 'P1b admin-initial-password.txt deleted by start.bat after password change' (-not (Test-Path $AdminPwFile))
  Check 'P1b meta endpoint 200 after restart' (Http-Ok 'http://localhost:18080/api/v1/meta')

  # ---------- P2b: Scenario E (multi-user real-time collaboration) ----------
  Phase 'P2b Scenario E (four browsers, real-time notifications)'
  $eLog = Join-Path $Evidence 'p2b-scenario-e.log'
  Push-Location (Join-Path $env:GITHUB_WORKSPACE 'tools\windows-e2e')
  try {
    $env:IB_BASE = 'http://localhost:18080'
    $env:IB_SHOTS = Join-Path $Evidence 'shots-e'
    & node e2e-scenario-e.mjs *>> $eLog
    Check 'P2b Scenario E passed' ($LASTEXITCODE -eq 0)
  } finally { Pop-Location }
  foreach ($shot in 'E1-invite-and-global-badge.png', 'E8-bell-panel-timezone.png', 'E6-acceptance-flow.png', 'E7-nudge-notify.png') {
    Check "P2b screenshot $shot exists" (Test-Path (Join-Path $Evidence "shots-e\$shot"))
  }

  # ---------- P2c: Scenario F (feedback flow, 1.1.0) ----------
  Phase 'P2c Scenario F (feedback: submit/+1/accept/notify/export)'
  $fLog = Join-Path $Evidence 'p2c-scenario-f.log'
  Push-Location (Join-Path $env:GITHUB_WORKSPACE 'tools\windows-e2e')
  try {
    $env:IB_BASE = 'http://localhost:18080'
    $env:IB_SHOTS = Join-Path $Evidence 'shots-f'
    $env:IB_ADMIN_PW = $script:AdminNewPw
    & node e2e-scenario-f.mjs *>> $fLog
    Check 'P2c Scenario F passed' ($LASTEXITCODE -eq 0)
  } finally { Pop-Location }
  foreach ($shot in 'F1-feedback-list.png', 'F2-bell-notification.png', 'F3-admin-view.png') {
    Check "P2c screenshot $shot exists" (Test-Path (Join-Path $Evidence "shots-f\$shot"))
  }

  # ---------- P3: stop, backup-skip while stopped, start ----------
  Phase 'P3 stop / backup-skip / start'
  $code = Run-Bat $Root 'stop.bat' @('--no-pause') 'p3-stop.log'
  Check 'P3 stop.bat exit 0' ($code -eq 0)
  # Timeline probe: sample the PG service state for 90 s after
  # stop.bat to see whether it stops at all, and if it comes back
  # on its own (SCM failure-action restart would show ~30 s later).
  $timeline = @()
  $pgStopped = $false
  $t0 = Get-Date
  while (((Get-Date) - $t0).TotalSeconds -lt 90) {
    $svc = Get-Service 'IterationBoardPG' -ErrorAction SilentlyContinue
    $st = if ($svc) { $svc.Status } else { 'MISSING' }
    $timeline += "$([int]((Get-Date) - $t0).TotalSeconds)s=$st"
    if ($st -eq 'Stopped') { $pgStopped = $true; break }
    Start-Sleep -Seconds 5
  }
  $qf = (& sc.exe qfailure IterationBoardPG | Out-String) -replace "[\r\n]+", ' '
  Write-Host "::notice::P3-TIMELINE $($timeline -join ' ') || qfailure: $qf"
  Check 'P3 PG service Stopped' $pgStopped
  Check 'P3 App service Stopped' (Wait-State 'IterationBoardApp' 'Stopped' 120)
  $code = Run-Bat $Root 'backup.bat' @('auto') 'p3-backup-skip.log'
  Check 'P3 backup.bat auto exit 0 while services stopped' ($code -eq 0)
  $blog = Get-Content (Join-Path $Root 'logs\backup.log') -Raw -ErrorAction SilentlyContinue
  Check 'P3 backup.log records the skip line' ($blog -match 'backup skipped')
  $code = Run-Bat $Root 'start.bat' @('--no-pause') 'p3-start.log'
  Check 'P3 start.bat exit 0' ($code -eq 0)
  Check 'P3 PG service Running again' (Wait-State 'IterationBoardPG' 'Running')
  Check 'P3 App service Running again' (Wait-State 'IterationBoardApp' 'Running')

  # ---------- P4: real backup ----------
  Phase 'P4 backup'
  $code = Run-Bat $Root 'backup.bat' @('auto') 'p4-backup.log'
  Check 'P4 backup.bat auto exit 0' ($code -eq 0)
  $bk = Get-ChildItem (Join-Path $Root 'backup') -Directory | Sort-Object Name | Select-Object -Last 1
  Check 'P4 db.dump produced' ($null -ne $bk -and (Test-Path (Join-Path $bk.FullName 'db.dump')) -and (Get-Item (Join-Path $bk.FullName 'db.dump')).Length -gt 0)
  $BackupName = $bk.Name

  # ---------- P5: restore ----------
  Phase 'P5 restore'
  $code = Run-Bat $Root 'restore.bat' @($BackupName, '--yes', '--no-pause') 'p5-restore.log'
  Check 'P5 restore.bat exit 0' ($code -eq 0)
  Check 'P5 meta endpoint 200 after restore' (Http-Ok 'http://localhost:18080/api/v1/meta')

  # ---------- P12: P23 backup status (1.1.0) ----------
  # Field-completeness is asserted unconditionally; the "no repeat
  # backup within 24 h" rule IS deterministic here because the stop
  # below produces a fresh success seconds before the start, so
  # start.bat must see a non-stale status and skip its pre-start
  # backup - the backup-folder count must not grow across the start.
  Phase 'P12 P23 backup status'
  $StatusFile = Join-Path $Root 'data\backup-status.json'
  Check 'P12 data\backup-status.json exists' (Test-Path $StatusFile)
  $st = $null
  if (Test-Path $StatusFile) {
    try { $st = Get-Content $StatusFile -Raw | ConvertFrom-Json } catch { }
  }
  Check 'P12 backup-status.json parses as JSON' ($null -ne $st)
  if ($st) {
    Check 'P12 status has lastSuccessAt and lastSuccessTime' (-not [string]::IsNullOrEmpty($st.lastSuccessAt) -and -not [string]::IsNullOrEmpty($st.lastSuccessTime))
    Check 'P12 status has lastAttemptAt and a lastResult value' (-not [string]::IsNullOrEmpty($st.lastAttemptAt) -and $st.lastResult -in @('success', 'failed', 'skipped'))
  }
  $bkDirs = { Get-ChildItem (Join-Path $Root 'backup') -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike 'upgrade-*' } }
  $bkCountBefore = (& $bkDirs).Count
  $code = Run-Bat $Root 'stop.bat' @('--no-pause') 'p12-stop.log'
  Check 'P12 stop.bat exit 0' ($code -eq 0)
  $st2 = $null
  if (Test-Path $StatusFile) { try { $st2 = Get-Content $StatusFile -Raw | ConvertFrom-Json } catch { } }
  Check 'P12 stop recorded a result (success or skipped)' ($null -ne $st2 -and $st2.lastResult -in @('success', 'skipped'))
  Check 'P12 backups dir has a fresh backup after stop' ((& $bkDirs).Count -gt $bkCountBefore)
  $bkCountAfterStop = (& $bkDirs).Count
  $code = Run-Bat $Root 'start.bat' @('--no-pause') 'p12-start.log'
  Check 'P12 start.bat exit 0' ($code -eq 0)
  Check 'P12 start within 24h of last success does NOT back up again' ((& $bkDirs).Count -eq $bkCountAfterStop)
  Check 'P12 meta endpoint 200 after start' (Http-Ok 'http://localhost:18080/api/v1/meta')

  # ---------- P13: migration export -> fresh B -> import (1.1.0) ----------
  # Service names are machine-global, so B can only be installed
  # after A is uninstalled (uninstall keeps A's data; A is
  # reinstalled at the end of this phase over its kept pgdata).
  # migrate-export.bat stops the app itself (--yes) and restarts it;
  # PostgreSQL stays up throughout the export.
  Phase 'P13 migration (A export -> B import)'
  $MarkerTitle = 'P13 迁移标记事项'
  $ciS13 = Api-Login 'ci_admin' $script:CiAdminPw
  $myTeams = Api-Call $ciS13 'Get' '/teams/mine' $null
  $ciTeam = $null
  if ($myTeams.Body) { $ciTeam = $myTeams.Body | Where-Object { $_.code -eq 'CI' } | Select-Object -First 1 }
  Check 'P13 marker team (code CI from P2) found on A' ($null -ne $ciTeam)
  if ($ciTeam) {
    $mk = Api-Call $ciS13 'Post' "/teams/$($ciTeam.id)/items" @{ type = 'DEFECT'; title = $MarkerTitle; destination = 'ASSIGN'; ownerIds = @((Api-Me $ciS13).id); priority = 'P2' }
    Check 'P13 marker item created on A' ($mk.Status -eq 200 -or $mk.Status -eq 201)
  }
  $code = Run-Bat $Root 'migrate-export.bat' @("`"$Root`"", '--yes', '--no-pause') 'p13-export.log'
  Check 'P13 migrate-export.bat exit 0' ($code -eq 0)
  $migZip = Get-ChildItem (Join-Path $Root 'iteration-board-migration-*.zip') -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
  $migVol = Get-ChildItem (Join-Path $Root 'iteration-board-migration-*.zip.001') -ErrorAction SilentlyContinue | Select-Object -First 1
  Check 'P13 migration package produced (zip or split .001)' ($null -ne $migZip -or $null -ne $migVol)
  $code = Run-Bat $Root 'uninstall.bat' @('--yes', '--no-pause') 'p13-uninstall-a.log'
  Check 'P13 uninstall A exit 0 (data kept)' ($code -eq 0)
  # Service names are machine-global: B's install can only register
  # IterationBoardApp/PG once A's services are fully deleted (a
  # just-deleted service lingers until its last handle closes).
  $settleDeadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $settleDeadline) {
    $appSvc = Get-Service -Name 'IterationBoardApp' -ErrorAction SilentlyContinue
    $pgSvc = Get-Service -Name 'IterationBoardPG' -ErrorAction SilentlyContinue
    if (-not $appSvc -and -not $pgSvc) { break }
    Start-Sleep -Seconds 3
  }
  # A's postmaster can outlive its deleted service and keep port
  # 15432 bound; B's install starts a temporary PG on that port and
  # fails if it is taken (second 1.1.0 runner round). Wait for the
  # port and the processes to clear (max 90 s), then force-kill any
  # leftover postgres.exe - the runner machine is disposable.
  $portDeadline = (Get-Date).AddSeconds(90)
  while ((Get-Date) -lt $portDeadline) {
    $listener = Get-NetTCPConnection -LocalPort 15432 -State Listen -ErrorAction SilentlyContinue
    $pgProcs = Get-Process -Name 'postgres' -ErrorAction SilentlyContinue
    if (-not $listener -and -not $pgProcs) { break }
    Start-Sleep -Seconds 3
  }
  Get-Process -Name 'postgres' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $Work 'pkgB') -Force
  $RootB = Join-Path $Work "pkgB\$PkgDirName"
  $script:RootB = $RootB
  $code = Run-Bat $RootB 'install.bat' @('--no-pause') 'p13-install-b.log'
  Check 'P13 install B exit 0' ($code -eq 0)
  $bPgLog = Join-Path $RootB 'logs\pg-init.log'
  if (Test-Path $bPgLog) { Copy-Item $bPgLog (Join-Path $Evidence 'p13-pg-init.log') -Force }
  Assert-Installed $RootB 'P13-B'
  $migPkgArg = $null
  if ($migVol) {
    Copy-Item (Join-Path $Root 'iteration-board-migration-*.zip.*') $RootB -Force
    if (Test-Path (Join-Path $Root 'merge-migration.bat')) { Copy-Item (Join-Path $Root 'merge-migration.bat') $RootB -Force }
    $migPkgArg = Join-Path $RootB $migVol.Name
  } elseif ($migZip) {
    Copy-Item $migZip.FullName $RootB -Force
    $migPkgArg = Join-Path $RootB $migZip.Name
  }
  $code = Run-Bat $RootB 'migrate-import.bat' @("`"$migPkgArg`"", "`"$RootB`"", '--yes', '--no-pause') 'p13-import.log'
  Check 'P13 migrate-import.bat exit 0' ($code -eq 0)
  $importLog = Get-Content (Join-Path $Evidence 'p13-import.log') -Raw -ErrorAction SilentlyContinue
  Check 'P13 import log: manifest hashes verified, no mismatch' ($importLog -match 'All files verified' -and $importLog -notmatch 'hash mismatch|Hash verification failed')
  Check 'P13 meta endpoint 200 on B after import' (Http-Ok 'http://localhost:18080/api/v1/meta')
  $ciB = $null
  try { $ciB = Api-Login 'ci_admin' $script:CiAdminPw } catch { Write-Host "  (B login failed: $($_.Exception.Message))" }
  Check 'P13 marker account (ci_admin) can log in on B' ($null -ne $ciB)
  if ($ciB) {
    $teamsB = Api-Call $ciB 'Get' '/teams/mine' $null
    $teamB = $null
    if ($teamsB.Body) { $teamB = $teamsB.Body | Where-Object { $_.code -eq 'CI' } | Select-Object -First 1 }
    Check 'P13 marker team present on B' ($null -ne $teamB)
    if ($teamB) {
      $itemsB = Api-Call $ciB 'Get' "/teams/$($teamB.id)/items" $null
      $found = $false
      if ($itemsB.Body) { $found = [bool]($itemsB.Body | Where-Object { $_.title -eq $MarkerTitle }) }
      Check 'P13 marker item present on B' $found
    }
  }
  $code = Run-Bat $RootB 'uninstall.bat' @('--yes', '--no-pause') 'p13-uninstall-b.log'
  Check 'P13 uninstall B exit 0' ($code -eq 0)
  $code = Run-Bat $Root 'install.bat' @('--no-pause') 'p13-reinstall-a.log'
  Check 'P13 reinstall A (kept pgdata) exit 0' ($code -eq 0)
  Assert-Installed $Root 'P13-A-back'

  # ---------- P6: upgrade in place (same package as its own new version) ----------
  Phase 'P6 upgrade (same package)'
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $Work 'pkg2') -Force
  $Root2 = Join-Path $Work "pkg2\$PkgDirName"
  $code = Run-Bat $Root2 'upgrade.bat' @("`"$Root`"", '--yes', '--no-pause') 'p6-upgrade.log'
  Check 'P6 upgrade.bat exit 0' ($code -eq 0)
  Check 'P6 PG service Running after upgrade' (Wait-State 'IterationBoardPG' 'Running')
  Check 'P6 App service Running after upgrade' (Wait-State 'IterationBoardApp' 'Running')
  Check 'P6 meta endpoint 200 after upgrade' (Http-Ok 'http://localhost:18080/api/v1/meta')

  # ---------- P14: upgrade + rollback bundle (1.1.0) ----------
  # Same-version upgrade is NOT refused by upgrade.bat (it has no
  # version-equality gate; P6 above already proves the overlay path),
  # so the rollback bundle mechanism is verified on this very path:
  # upgrade -> bundle with upgrade-info.txt exists -> rollback.bat ->
  # app healthy and the marker account still logs in (the rollback
  # restores the pre-upgrade database backup taken minutes earlier,
  # which already contains the P13 marker data and the rotated
  # admin password from P1b).
  Phase 'P14 upgrade + rollback'
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $Work 'pkg3') -Force
  $Root3 = Join-Path $Work "pkg3\$PkgDirName"
  $code = Run-Bat $Root3 'upgrade.bat' @("`"$Root`"", '--yes', '--no-pause') 'p14-upgrade.log'
  Check 'P14 upgrade.bat exit 0 (same-version overlay)' ($code -eq 0)
  $bundle = Get-ChildItem (Join-Path $Root 'backup') -Directory -Filter 'upgrade-*' -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -Last 1
  Check 'P14 rollback bundle upgrade-<ts> recorded' ($null -ne $bundle)
  if ($bundle) {
    Check 'P14 bundle contains upgrade-info.txt' (Test-Path (Join-Path $bundle.FullName 'upgrade-info.txt'))
    $info = Get-Content (Join-Path $bundle.FullName 'upgrade-info.txt') -Raw -ErrorAction SilentlyContinue
    Check 'P14 upgrade-info.txt records DbBackup and versions' ($info -match 'DbBackup=' -and $info -match 'FromVersion=' -and $info -match 'ToVersion=')
  }
  Check 'P14 meta endpoint 200 after upgrade' (Http-Ok 'http://localhost:18080/api/v1/meta')
  $code = Run-Bat $Root 'rollback.bat' @('--yes', '--no-pause') 'p14-rollback.log'
  Check 'P14 rollback.bat exit 0' ($code -eq 0)
  Check 'P14 App service Running after rollback' (Wait-State 'IterationBoardApp' 'Running' 180)
  Check 'P14 meta endpoint 200 after rollback' (Http-Ok 'http://localhost:18080/api/v1/meta')
  $ciAfterRb = $null
  try { $ciAfterRb = Api-Login 'ci_admin' $script:CiAdminPw } catch { Write-Host "  (post-rollback login failed: $($_.Exception.Message))" }
  Check 'P14 marker account still logs in after rollback' ($null -ne $ciAfterRb)

  # ---------- P7: uninstall keeping data ----------
  Phase 'P7 uninstall (keep data)'
  $code = Run-Bat $Root 'uninstall.bat' @('--yes', '--no-pause') 'p7-uninstall.log'
  Check 'P7 uninstall.bat exit 0' ($code -eq 0)
  Check 'P7 PG service removed' ($null -eq (Get-Service 'IterationBoardPG' -ErrorAction SilentlyContinue))
  Check 'P7 App service removed' ($null -eq (Get-Service 'IterationBoardApp' -ErrorAction SilentlyContinue))
  Check 'P7 pgdata kept' (Test-Path (Join-Path $Root 'data\pgdata\PG_VERSION'))

  # ---------- P8: reinstall over the half-installed state ----------
  Phase 'P8 reinstall (recovery path)'
  $code = Run-Bat $Root 'install.bat' @('--no-pause') 'p8-reinstall.log'
  Check 'P8 install.bat exit 0' ($code -eq 0)
  Assert-Installed $Root 'P8'

  # ---------- P9: uninstall again ----------
  Phase 'P9 uninstall'
  $code = Run-Bat $Root 'uninstall.bat' @('--yes', '--no-pause') 'p9-uninstall.log'
  Check 'P9 uninstall.bat exit 0' ($code -eq 0)
  Check 'P9 services removed' ($null -eq (Get-Service 'IterationBoardPG' -ErrorAction SilentlyContinue))

  # ---------- P10: install into a path with spaces ----------
  Phase 'P10 install into path with spaces'
  $SpaceBase = 'C:\ib space test'
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $SpaceBase 'pkg') -Force
  $SRoot = Join-Path $SpaceBase "pkg\$PkgDirName"
  $code = Run-Bat $SRoot 'install.bat' @('--no-pause') 'p10-space-install.log'
  Check 'P10 install.bat exit 0 despite spaces in path' ($code -eq 0)
  Check 'P10 PG service Running' (Wait-State 'IterationBoardPG' 'Running')
  Check 'P10 meta endpoint 200' (Http-Ok 'http://localhost:18080/api/v1/meta')
  $code = Run-Bat $SRoot 'uninstall.bat' @('--yes', '--no-pause') 'p10-space-uninstall.log'
  Check 'P10 uninstall.bat exit 0' ($code -eq 0)

  # ---------- P11: --autostart mode ----------
  Phase 'P11 install with --autostart'
  $code = Run-Bat $Root 'install.bat' @('--autostart', '--no-pause') 'p11-autostart-install.log'
  Check 'P11 install.bat --autostart exit 0' ($code -eq 0)
  Check 'P11 PG service START_TYPE is AUTO_START' ((ScQc 'IterationBoardPG') -match 'AUTO_START')
  Check 'P11 App service START_TYPE is AUTO_START' ((ScQc 'IterationBoardApp') -match 'AUTO_START')
  Check 'P11 services Running' ((Wait-State 'IterationBoardPG' 'Running') -and (Wait-State 'IterationBoardApp' 'Running'))
  $code = Run-Bat $Root 'uninstall.bat' @('--yes', '--no-pause') 'p11-uninstall.log'
  Check 'P11 final uninstall exit 0' ($code -eq 0)
} catch {
  Write-Host "FATAL: $($_.Exception.Message)"
  Write-Host "::error::FATAL $($_.Exception.Message)"
  $script:Failures += "FATAL: $($_.Exception.Message)"
} finally {
  # Keep the application's own logs as evidence whatever happened.
  foreach ($r in @($Root, $SRoot, $script:RootB)) {
    if ($r -and (Test-Path (Join-Path $r 'logs'))) {
      $dest = Join-Path $Evidence ("applogs-" + ($r -replace '[:\\ ]', '_'))
      Copy-Item (Join-Path $r 'logs') $dest -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}

Write-Host ''
if ($script:Failures.Count -gt 0) {
  Write-Host "WINDOWS VERIFY: $($script:Failures.Count) FAILURE(S)"
  $script:Failures | ForEach-Object { Write-Host "  - $_" }
  if ($script:Failures -match 'P1 ') { Dump-Tail 'p1-install.log' }
  if ($script:Failures -match 'P1b') { Dump-Tail 'p1-install.log'; Dump-Tail 'p1b-stop.log'; Dump-Tail 'p1b-start.log' }
  if ($script:Failures -match 'P12') { Dump-Tail 'p12-stop.log'; Dump-Tail 'p12-start.log' }
  if ($script:Failures -match 'P13') { Dump-Tail 'p13-install-b.log' 25; Dump-Tail 'p13-uninstall-a.log'; Dump-Tail 'p13-export.log'; Dump-Tail 'p13-pg-init.log' 25 }
  if ($script:Failures -match 'P1b') {
    $p1log = Join-Path $Evidence 'p1-install.log'
    if (Test-Path $p1log) {
      $hits = (Select-String -Path $p1log -Pattern 'Step 9|admin|WARN' | Select-Object -First 14 | ForEach-Object { $_.Line.Trim() }) -join ' // '
      if ($hits.Length -gt 900) { $hits = $hits.Substring(0, 900) }
      $hits = $hits -replace '%', '%25' -replace "`r", '%0D' -replace "`n", '%0A'
      Write-Host "::error::P1-ADMIN-LINES: $hits"
    }
  }
  if ($script:Failures -match 'P2 ') { Dump-Tail 'p2-playwright.log' }
  if ($script:Failures -match 'P2b ') { Dump-Tail 'p2b-scenario-e.log' }
  if ($script:Failures -match 'P2c ') { Dump-Tail 'p2c-scenario-f.log' }
  if ($script:Failures -match 'P3 ') { Dump-Tail 'p3-stop.log' }
  if ($script:Failures -match 'P6 ') { Dump-Tail 'p6-upgrade.log' }
  if ($script:Failures -match 'P13') { Dump-Tail 'p13-import.log' }
  if ($script:Failures -match 'P14') { Dump-Tail 'p14-rollback.log' }
  if ($script:Failures -match 'P10 ') { Dump-Tail 'p10-space-install.log' }
  $joined = ($script:Failures -join ' | ') -replace '%', '%25'
  Write-Host "::error::WINDOWS VERIFY RESULT: $joined"
  exit 1
}
Write-Host "::notice::WINDOWS VERIFY RESULT: all checks passed"
Write-Host 'WINDOWS VERIFY: ALL CHECKS PASSED'
exit 0
