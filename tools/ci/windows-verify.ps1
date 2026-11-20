# ============================================================
# Windows runner end-to-end verification for the offline package.
# Invoked by .github/workflows/windows-install.yml on
# windows-latest (the runner user is a local administrator).
#
# Expects the built package zip at C:\ibdl\iteration-board-offline-1.0.4.zip
# (downloaded from the build job's artifact by the workflow).
# All console output of every .bat run is kept under
# C:\ibwork\evidence and uploaded as a workflow artifact,
# together with the Playwright screenshots.
#
# Phases:
#   P1  fresh install (no-space path, --no-pause), full assertions
#   P2  Playwright direct-visit flow + 3 screenshots (8eyn4mwp)
#   P3  stop -> backup must SKIP with exit 0 while PG is stopped
#       (hbztskr3) -> start
#   P4  backup (auto) produces backup\<ts>\db.dump
#   P5  restore that backup
#   P6  upgrade in place from a second copy of the same package
#   P7  uninstall keeping data (services gone, pgdata kept)
#   P8  reinstall over the half-installed state (recovery path,
#       re66unpr): existing config + pgdata are reused
#   P9  uninstall again
#   P10 install into a path WITH spaces (warning only, continues)
#   P11 install with --autostart: both services AUTO_START
#       (hbztskr3); P1 asserted DEMAND_START for the default mode
# ============================================================
$ErrorActionPreference = 'Continue'
$Zip = 'C:\ibdl\iteration-board-offline-1.0.4.zip'
$Work = 'C:\ibwork'
$Evidence = Join-Path $Work 'evidence'
New-Item -ItemType Directory -Force $Evidence | Out-Null
$script:Failures = @()

function Check([string]$Name, [bool]$Ok) {
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
  $Root = Join-Path $Work 'pkg\iteration-board-offline-1.0.4'
  $code = Run-Bat $Root 'install.bat' @('--no-pause') 'p1-install.log'
  Check 'P1 install.bat exit 0' ($code -eq 0)
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
  foreach ($shot in '01-home-login.png', '02-workbench.png', '03-board-after-refresh.png', '04-admin.png') {
    Check "P2 screenshot $shot exists" (Test-Path (Join-Path $Evidence "shots\$shot"))
  }

  # ---------- P2b: Scenario E (multi-user real-time collaboration, 1.0.4) ----------
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

  # ---------- P6: upgrade in place (same package as its own new version) ----------
  Phase 'P6 upgrade (same package)'
  Expand-Archive -Path $Zip -DestinationPath (Join-Path $Work 'pkg2') -Force
  $Root2 = Join-Path $Work 'pkg2\iteration-board-offline-1.0.4'
  $code = Run-Bat $Root2 'upgrade.bat' @("`"$Root`"", '--yes', '--no-pause') 'p6-upgrade.log'
  Check 'P6 upgrade.bat exit 0' ($code -eq 0)
  Check 'P6 PG service Running after upgrade' (Wait-State 'IterationBoardPG' 'Running')
  Check 'P6 App service Running after upgrade' (Wait-State 'IterationBoardApp' 'Running')
  Check 'P6 meta endpoint 200 after upgrade' (Http-Ok 'http://localhost:18080/api/v1/meta')

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
  $SRoot = Join-Path $SpaceBase 'pkg\iteration-board-offline-1.0.4'
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
  foreach ($r in @($Root, $SRoot)) {
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
  if ($script:Failures -match 'P2 ') { Dump-Tail 'p2-playwright.log' }
  if ($script:Failures -match 'P2b ') { Dump-Tail 'p2b-scenario-e.log' }
  if ($script:Failures -match 'P3 ') { Dump-Tail 'p3-stop.log' }
  if ($script:Failures -match 'P6 ') { Dump-Tail 'p6-upgrade.log' }
  if ($script:Failures -match 'P10 ') { Dump-Tail 'p10-space-install.log' }
  $joined = ($script:Failures -join ' | ') -replace '%', '%25'
  Write-Host "::error::WINDOWS VERIFY RESULT: $joined"
  exit 1
}
Write-Host "::notice::WINDOWS VERIFY RESULT: all checks passed"
Write-Host 'WINDOWS VERIFY: ALL CHECKS PASSED'
exit 0
