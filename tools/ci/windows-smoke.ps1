# Windows 冒烟（CI 档 2，Claude 裁定 2026-11-20 诊断策略）：
#   S0 中文路径拦截断言（裁定 A，3wzzexbc：install 必须非 0 退出、
#   输出路径提示、且未注册服务/未建数据目录）→ 解包到空格路径 →
#   install（空格路径、中文 TEMP、chcp 936）→ 健康检查
#   → 用密码文件登录 admin → 改密（应用应删除密码文件）→ stop → start
#   → 用新密码登录 → uninstall。每步输出单独落证据目录，失败不中断
#   证据收集。目标 ≤20 分钟；全量 E2E 在档 3（windows-verify.ps1），
#   只在本冒烟全绿后跑。
# 用法：pwsh -File tools/ci/windows-smoke.ps1 [-ZipPath C:\ibdl\iteration-board-offline-1.1.0.zip]
param(
  [string]$ZipPath = 'C:\ibdl\iteration-board-offline-1.1.0.zip',
  [string]$WorkRoot = 'C:\ibwork-smoke',
  [string]$ChromeExe = ''
)
$ErrorActionPreference = 'Continue'
$script:Failures = @()
$Evidence = Join-Path $WorkRoot 'evidence'
$BlockedParent = Join-Path $WorkRoot '迭代 平台'
$BlockedRoot = Join-Path $BlockedParent 'iteration-board-offline-1.1.0'
$PkgParent = Join-Path $WorkRoot 'install folder'
$Root = Join-Path $PkgParent 'iteration-board-offline-1.1.0'
$ChineseTemp = Join-Path $WorkRoot '临时目录'
New-Item -ItemType Directory -Force -Path $Evidence, $BlockedParent, $PkgParent, $ChineseTemp | Out-Null
# 子进程的 TEMP/TMP 指向中文目录（模拟中文用户名环境）
$env:TEMP = $ChineseTemp
$env:TMP = $ChineseTemp

function Check([string]$Name, [bool]$Ok) {
  if ($Ok) { Write-Host "PASS $Name" } else { Write-Host "::error::FAIL $Name"; $script:Failures += $Name }
}
function Run-Bat([string]$Bat, [string[]]$BatArgs, [string]$LogName, [switch]$Gbk, [string]$Cwd = $Root) {
  $log = Join-Path $Evidence $LogName
  "`r`n===== $Bat $($BatArgs -join ' ')  (cwd: $Cwd) =====" | Out-File -Append -Encoding utf8 $log
  Push-Location $Cwd
  try {
    if ($Gbk) { & cmd.exe /c "chcp 936 >nul && .\$Bat $($BatArgs -join ' ') >> `"$log`" 2>&1" }
    else { & cmd.exe /c ".\$Bat $($BatArgs -join ' ') >> `"$log`" 2>&1" }
    return $LASTEXITCODE
  } finally { Pop-Location }
}
function Wait-Health([int]$Seconds = 150) {
  $deadline = (Get-Date).AddSeconds($Seconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/auth/captcha' -TimeoutSec 5
      if ($r.StatusCode -eq 200) { return $true }
    } catch { }
    Start-Sleep -Seconds 3
  }
  return $false
}
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
  try { Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/auth/captcha' -WebSession $s -TimeoutSec 10 | Out-Null } catch { }
  $r = Api-Call $s 'Post' '/auth/login' @{ loginName = $User; password = $Pass }
  if ($r.Status -ne 200) { throw "API login failed for ${User}: HTTP $($r.Status) $($r.Raw)" }
  return $s
}

try {
  Write-Host 'PHASE S0 blocked-path assertion (Chinese install path, ruling A)'
  Expand-Archive -Path $ZipPath -DestinationPath $BlockedParent -Force
  Check 'S0 package unpacked to Chinese path (install.bat present)' (Test-Path (Join-Path $BlockedRoot 'install.bat'))
  $code = Run-Bat 'install.bat' @('--no-pause') 'smoke-blocked.log' -Gbk -Cwd $BlockedRoot
  Check 'S0 install.bat blocked: exit code non-zero' ($code -ne 0)
  $blockedLog = Get-Content (Join-Path $Evidence 'smoke-blocked.log') -Raw -ErrorAction SilentlyContinue
  Check 'S0 install.bat blocked: path message printed' ($blockedLog -match 'The install folder path contains non-English characters')
  $svcCount = @(Get-Service -Name 'IterationBoardPG', 'IterationBoardApp' -ErrorAction SilentlyContinue).Count
  Check 'S0 install.bat blocked: no services registered' ($svcCount -eq 0)
  Check 'S0 install.bat blocked: no data directory created' (-not (Test-Path (Join-Path $BlockedRoot 'data')))

  Write-Host "PHASE S1 unpack -> $PkgParent"
  Expand-Archive -Path $ZipPath -DestinationPath $PkgParent -Force
  Check 'S1 package unpacked (install.bat present)' (Test-Path (Join-Path $Root 'install.bat'))

  Write-Host 'PHASE S2 install (chcp 936, Chinese TEMP, space-only path)'
  $code = Run-Bat 'install.bat' @('--no-pause') 'smoke-install.log' -Gbk
  Check 'S2 install.bat exit 0' ($code -eq 0)
  Check 'S2 app healthy after install' (Wait-Health)

  Write-Host 'PHASE S3 admin bootstrap (app-side, password file)'
  $pwFile = Join-Path $Root 'config\admin-initial-password.txt'
  Check 'S3 admin-initial-password.txt exists' (Test-Path $pwFile)
  $initPw = $null
  if (Test-Path $pwFile) {
    $m = Select-String -Path $pwFile -Pattern 'admin initial password:\s*(\S+)' | Select-Object -First 1
    if ($m) { $initPw = $m.Matches[0].Groups[1].Value }
  }
  Check 'S3 initial password parsed, length >= 16' ($null -ne $initPw -and $initPw.Length -ge 16)
  $NewPw = 'SmokeAdmin2026x'
  if ($initPw) {
    $s = Api-Login 'admin' $initPw
    $me = Api-Call $s 'Get' '/auth/me' $null
    Check 'S3 admin is platformAdmin + mustChangePassword' ($me.Body.platformAdmin -eq $true -and $me.Body.mustChangePassword -eq $true)
    $chg = Api-Call $s 'Post' '/auth/change-password' @{ oldPassword = $initPw; newPassword = $NewPw }
    Check 'S3 change-password succeeds (204)' ($chg.Status -eq 204)
    Start-Sleep -Seconds 1
    Check 'S3 password file deleted by app after change' (-not (Test-Path $pwFile))
  }

  Write-Host 'PHASE S4 stop / start'
  $code = Run-Bat 'stop.bat' @('--no-pause') 'smoke-stop.log'
  Check 'S4 stop.bat exit 0' ($code -eq 0)
  Start-Sleep -Seconds 3
  $code = Run-Bat 'start.bat' @('--no-pause') 'smoke-start.log'
  Check 'S4 start.bat exit 0' ($code -eq 0)
  Check 'S4 app healthy after restart' (Wait-Health)
  if ($initPw) {
    $s2 = Api-Login 'admin' $NewPw
    Check 'S4 login with new password works' ($null -ne $s2)
  }

  Write-Host 'PHASE S5 Chrome 128 real-browser check (Chrome for Testing 128.0.6613.x)'
  if ($ChromeExe -and (Test-Path $ChromeExe)) {
    $shot = Join-Path $Evidence 'chrome128-login.png'
    $domFile = Join-Path $Evidence 'chrome128-dom.html'
    & $ChromeExe --headless=new --disable-gpu --no-sandbox --window-size=1440,900 --screenshot=$shot 'http://localhost:18080/' 2>&1 | Out-Null
    Check 'S5 Chrome 128 renders login page (screenshot exists)' (Test-Path $shot)
    & $ChromeExe --headless=new --disable-gpu --no-sandbox --dump-dom 'http://localhost:18080/' 2>&1 | Out-File -Encoding utf8 $domFile
    $dom = Get-Content $domFile -Raw -ErrorAction SilentlyContinue
    Check 'S5 Chrome 128 DOM contains app login marker' ($dom -match 'demo-box|login|登录')
  } else {
    Write-Host 'SKIP S5 (no ChromeExe provided)'
  }

  Write-Host 'PHASE S6 uninstall'
  $code = Run-Bat 'uninstall.bat' @('--yes', '--no-pause') 'smoke-uninstall.log'
  Check 'S6 uninstall.bat exit 0' ($code -eq 0)
} catch {
  Write-Host "::error::SMOKE FATAL: $($_.Exception.Message)"
  $script:Failures += "FATAL: $($_.Exception.Message)"
} finally {
  # 证据：脚本输出已在 $Evidence；补应用/PG/WinSW 日志与 SCM 事件
  try {
    $logsDir = Join-Path $Root 'logs'
    if (Test-Path $logsDir) { Copy-Item $logsDir -Destination (Join-Path $Evidence 'app-logs') -Recurse -Force -ErrorAction SilentlyContinue }
    $svcDir = Join-Path $Root 'service'
    if (Test-Path $svcDir) { Get-ChildItem $svcDir -Filter '*.log' -ErrorAction SilentlyContinue | Copy-Item -Destination $Evidence -Force -ErrorAction SilentlyContinue }
    $pgLog = Join-Path $Root 'data\pg_log'
    if (Test-Path $pgLog) { Copy-Item $pgLog -Destination (Join-Path $Evidence 'pg-log') -Recurse -Force -ErrorAction SilentlyContinue }
    Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Service Control Manager'; StartTime = (Get-Date).AddHours(-3) } -MaxEvents 50 -ErrorAction SilentlyContinue |
      Format-List TimeCreated, Id, LevelDisplayName, Message | Out-File -Encoding utf8 (Join-Path $Evidence 'scm-events.txt')
  } catch { }
  if ($script:Failures.Count -gt 0) {
    Write-Host "SMOKE RESULT: $($script:Failures.Count) FAILURE(S)"
    $script:Failures | ForEach-Object { Write-Host "  - $_" }
    # 证据 artifact 无权限下载时，关键日志尾巴经 annotation 表面化（同 windows-verify.ps1 口径）
    foreach ($ln in @('smoke-blocked.log', 'smoke-install.log', 'smoke-stop.log', 'smoke-start.log', 'smoke-uninstall.log')) {
      $lf = Join-Path $Evidence $ln
      if (Test-Path $lf) {
        $tail = (Get-Content $lf -Tail 25 | ForEach-Object { $_.Trim() }) -join ' // '
        if ($tail.Length -gt 900) { $tail = $tail.Substring(0, 900) }
        $tail = $tail -replace '%', '%25' -replace "`r", '%0D' -replace "`n", '%0A'
        Write-Host "::error::LOGTAIL ${ln}: $tail"
      }
    }
    # 安装器自身日志（logs\）与 PG 日志（data\pg_log\）尾巴一并表面化：
    # 阶段日志只到步骤横幅，真正的失败行在这些文件里。
    foreach ($sub in @('logs', 'data\pg_log')) {
      $dir = Join-Path $Root $sub
      if (Test-Path $dir) {
        Get-ChildItem $dir -File -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 3 |
          ForEach-Object {
            $tail = (Get-Content $_.FullName -Tail 18 | ForEach-Object { $_.Trim() }) -join ' // '
            if ($tail.Length -gt 900) { $tail = $tail.Substring(0, 900) }
            $tail = $tail -replace '%', '%25' -replace "`r", '%0D' -replace "`n", '%0A'
            Write-Host "::error::LOGTAIL ${sub}/$($_.Name): $tail"
          }
      }
    }
    exit 1
  }
  Write-Host 'SMOKE RESULT: ALL PASS'
  exit 0
}
