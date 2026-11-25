# Windows 恶劣路径矩阵（1.1.0 脚本专项，任务 rucc9a9y / 7pjby64d 落地）：
#   补丁/安装包放在含空格、括号 "(1)"、中文、"Program Files (x86)" 风格的
#   路径下，以「双击方式（不带参数，靠服务反查自动检测）」和「带安装路径
#   参数」两种方式运行 apply-patch / install / backup / restore /
#   reset-admin，断言：不闪退（脚本日志完整落盘 + 明确结束行）、目标目录
#   识别正确、返回码正确；并实跑 1.0.4 / 1.0.1 夹具安装的补丁升级路径
#   （Gemini 前置把控采纳项：跨版本补丁不得裸奔）。
# 设计口径：
#   - 安装目标目录必须是英文路径（产品裁定 A，中文路径应被 install 拦截）；
#     恶劣字符加在「补丁包所在目录」与「英文+空格+括号的安装目录」上。
#   - 全部子进程带 IB_AUTO=1（不套 cmd /k 壳、不 pause）与 IB_YES=1
#     （确认类提问自动肯定），断言以退出码 + 日志文件为准，这正是新脚本
#     契约给 CI 的接口；人工双击路径由 IB_WRAPPED 外壳保证窗口留存，
#     无法在 CI 里模拟点击，靠日志完整性断言等价覆盖。
# 用法（档 3 windows-verify 之后或手动）：
#   pwsh -File tools/ci/windows-nasty-paths.ps1 -FullZip C:\ibdl\iteration-board-offline-1.1.0.zip `
#     [-Rc4Zip C:\ibdl\iteration-board-offline-1.1.0-rc4.zip -PatchZip C:\ibdl\iteration-board-patch-1.1.0-rc4-to-1.1.0.zip] `
#     [-OldZip104 C:\ibdl\iteration-board-offline-1.0.4.zip -Patch104 C:\ibdl\iteration-board-patch-1.0.4-to-1.1.0.zip] `
#     [-OldZip101 C:\ibdl\iteration-board-offline-1.0.1.zip -Patch101 C:\ibdl\iteration-board-patch-1.0.1-to-1.1.0.zip]
param(
  [Parameter(Mandatory = $true)][string]$FullZip,
  [string]$Rc4Zip,
  [string]$PatchZip,
  [string]$OldZip104,
  [string]$Patch104,
  [string]$OldZip101,
  [string]$Patch101,
  [string]$Evidence = 'C:\ibnasty\evidence'
)
$ErrorActionPreference = 'Continue'
$script:Failures = @()
$Base = 'C:\ibnasty'
New-Item -ItemType Directory -Force -Path $Evidence | Out-Null

function Check([string]$Name, [bool]$Ok) {
  if ($Ok) { Write-Host "PASS $Name" } else { Write-Host "::error::FAIL $Name"; $script:Failures += $Name }
}
function Run-BatAuto([string]$BatPath, [string[]]$BatArgs, [string]$LogName, [string]$Cwd, [string]$StdinText = $null) {
  # IB_AUTO/IB_YES 下运行 .bat，返回退出码；控制台输出落证据目录。
  # 经 Process API 以 Arguments 原样串启动 cmd.exe：/c 后跟整行
  # （"<bat>" "<参数>"…）再外包一对引号，cmd 的 /c 规则剥掉外层
  # 一对、内层引号完整保留。两种经 PowerShell & 调用的写法都实测
  # 失败：参数逐个传时 cmd 的引号剥离把带空格路径拆断（run
  # 45362168304：'C:\ibnasty\patch' is not recognized）；整行作
  # 单个参数传时 PowerShell 对原生命令参数二次加引号，连程序
  # 路径都被拆断（run 45377864212 / 45378969080：'""C:\ibnasty
  # \patch' is not recognized）。ProcessStartInfo.Arguments 不经
  # PowerShell 参数层，原样到达 cmd。
  # $StdinText 非 $null 时写入子进程 stdin 后关闭（老版本
  # install 的 choice 提问、apply-patch 手动输入分支靠它喂入）。
  $env:IB_AUTO = '1'; $env:IB_YES = '1'
  $out = Join-Path $Evidence $LogName
  $cmdLine = '"' + $BatPath + '"'
  foreach ($a in $BatArgs) { $cmdLine += ' "' + $a + '"' }
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = "$env:SystemRoot\System32\cmd.exe"
  $psi.Arguments = '/c "' + $cmdLine + '"'
  $psi.WorkingDirectory = $Cwd
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  if ($null -ne $StdinText) { $psi.RedirectStandardInput = $true }
  $proc = New-Object System.Diagnostics.Process
  $proc.StartInfo = $psi
  [void]$proc.Start()
  $outTask = $proc.StandardOutput.ReadToEndAsync()
  $errTask = $proc.StandardError.ReadToEndAsync()
  if ($null -ne $StdinText) {
    try { $proc.StandardInput.Write($StdinText); $proc.StandardInput.Close() } catch { }
  }
  if (-not $proc.WaitForExit(1200000)) {
    try { $proc.Kill($true) } catch { }
    [void]$proc.WaitForExit()
  }
  $outText = ''
  $errText = ''
  if ($outTask.Wait(30000)) { $outText = $outTask.Result }
  if ($errTask.Wait(30000)) { $errText = $errTask.Result }
  ($outText + $errText) | Out-File -Encoding utf8 $out
  return $proc.ExitCode
}
function Latest-ScriptLog([string]$Dir, [string]$Prefix) {
  Get-ChildItem $Dir -Filter "$Prefix-*.log" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
function Wait-Health([int]$Seconds = 150) {
  for ($i = 0; $i -lt $Seconds; $i += 3) {
    try {
      $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/meta' -TimeoutSec 5
      if ($r.StatusCode -eq 200) { return $true }
    } catch { }
    Start-Sleep -Seconds 3
  }
  return $false
}
function Get-MetaVersion {
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/meta' -TimeoutSec 5
    return ($r.Content | ConvertFrom-Json).version
  } catch { return $null }
}
function Wait-HealthLegacy([int]$Seconds = 150) {
  # Legacy (pre-1.1.0) jars have no /api/v1/meta - that endpoint
  # arrived with the 1.1.0 line. The captcha endpoint exists in
  # every shipped version and is what the smoke tier probes.
  for ($i = 0; $i -lt $Seconds; $i += 3) {
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
function Api-Register([string]$Login, [string]$Display, [string]$Pass) {
  # Registration demands a captcha; the code glyphs are <text>
  # nodes in the issued SVG (same approach as tools/windows-e2e).
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  $cap = Api-Call $s 'Get' '/auth/captcha' $null
  if ($cap.Status -ne 200 -or -not $cap.Body) { return $null }
  $glyphs = [regex]::Matches([string]$cap.Body.imageSvg, '>(.)</text>') | ForEach-Object { $_.Groups[1].Value }
  $code = -join $glyphs
  if ($code.Length -lt 4) { return $null }
  $r = Api-Call $s 'Post' '/auth/register' @{ loginName = $Login; displayName = $Display; password = $Pass; captchaId = $cap.Body.captchaId; captchaCode = $code }
  if ($r.Status -ge 300) { return $null }
  return $s
}
function Api-Login([string]$User, [string]$Pass) {
  $s = New-Object Microsoft.PowerShell.Commands.WebRequestSession
  try { Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:18080/api/v1/auth/captcha' -WebSession $s -TimeoutSec 10 | Out-Null } catch { }
  $r = Api-Call $s 'Post' '/auth/login' @{ loginName = $User; password = $Pass }
  if ($r.Status -ne 200) { return $null }
  return $s
}
function Invoke-PsqlScalar([string]$RootPath, [string]$Sql) {
  $pwdLine = Select-String -Path (Join-Path $RootPath 'config\application.yml') -Pattern '^\s*password:\s*(\S+)' | Select-Object -First 1
  if (-not $pwdLine) { return $null }
  $env:PGPASSWORD = $pwdLine.Matches[0].Groups[1].Value
  try {
    $out = & (Join-Path $RootPath 'runtime\pgsql\bin\psql.exe') -h 127.0.0.1 -p 15432 -U postgres -d iteration_board -q -tAc $Sql
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($out | Out-String).Trim()
  } finally { $env:PGPASSWORD = $null }
}
function Install-Fresh([string]$Zip, [string]$TargetDir, [string]$Tag = '') {
  # 解包并把包内根目录改名/定位到 $TargetDir，再 install（--no-pause）。
  $parent = Split-Path $TargetDir -Parent
  New-Item -ItemType Directory -Force -Path $parent | Out-Null
  Expand-Archive -Path $Zip -DestinationPath $parent -Force
  if (-not (Test-Path (Join-Path $TargetDir 'install.bat'))) {
    $found = Get-ChildItem $parent -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'install.bat') } | Select-Object -First 1
    if ($found -and $found.FullName -ne $TargetDir) { Rename-Item $found.FullName (Split-Path $TargetDir -Leaf) }
  }
  # stdin 喂入 10 行 Y：1.0.1 的 install.bat 有 choice /C YN 提问，
  # stdin 为空时 choice 直接报「The file is either empty...」并卡
  # 住（1.1.0 终验 N3-1.0.1 实测）；新版脚本在 IB_AUTO 下不提问，
  # 多余输入被丢弃，无副作用。
  $answers = (@('Y') * 10) -join "`r`n"
  $logName = 'tmp-install.log'
  if ($Tag) { $logName = "install-$Tag.log" }
  $code = Run-BatAuto (Join-Path $TargetDir 'install.bat') @('--no-pause') $logName $TargetDir $answers
  if ($Tag) { Dump-Forensics "install-$Tag" $TargetDir }
  return $code
}
function Dump-Forensics([string]$Tag, [string]$TargetDir) {
  # Gemini 补证清单（1.1.0 终验修复轮）：在关键节点与失败现场
  # 留存服务登记原值、进程命令行、端口占用、口令脱敏指纹、
  # PG_VERSION 时间戳与检测器单跑输出。本函数只读、绝不抛错，
  # 证据缺失时如实记 <absent>。
  $safe = ($Tag -replace '[^\w.-]+', '_')
  $f = Join-Path $Evidence "forensics-$safe.txt"
  $lines = @()
  try {
    $lines += "== forensics $Tag at $(Get-Date -Format o) target=$TargetDir"
    foreach ($svc in @('IterationBoardApp', 'IterationBoardPG')) {
      $reg = $null
      try { $reg = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\$svc" -ErrorAction Stop).ImagePath } catch { }
      if ($reg) { $lines += "$svc registry ImagePath: $reg" } else { $lines += "$svc registry ImagePath: <absent>" }
      $w = Get-CimInstance Win32_Service -Filter "Name='$svc'" -ErrorAction SilentlyContinue
      if ($w) { $lines += "$svc Win32_Service: State=$($w.State) StartMode=$($w.StartMode) PathName=$($w.PathName)" } else { $lines += "$svc Win32_Service: <absent>" }
      $qx = (& sc.exe queryex $svc 2>&1 | Out-String).Trim()
      $lines += "$svc sc queryex: $qx"
    }
    $procs = Get-CimInstance Win32_Process -Filter "Name='postgres.exe' or Name='java.exe'" -ErrorAction SilentlyContinue
    if ($procs) {
      foreach ($pr in $procs) { $lines += "proc $($pr.Name) pid=$($pr.ProcessId): $($pr.CommandLine)" }
    } else { $lines += 'proc: none' }
    $ns = & netstat.exe -ano 2>$null | Select-String ':15432'
    if ($ns) { foreach ($n in $ns) { $lines += "netstat: $($n.Line.Trim())" } } else { $lines += 'netstat :15432: <none>' }
    if ($TargetDir) {
      $yml = Join-Path $TargetDir 'config\application.yml'
      if (Test-Path $yml) {
        $m = Select-String -Path $yml -Pattern '^\s*password:\s*(\S+)' | Select-Object -First 1
        if ($m) {
          $pw = $m.Matches[0].Groups[1].Value
          $lines += "yml password: len=$($pw.Length) prefix=$($pw.Substring(0, [Math]::Min(4, $pw.Length)))"
        } else { $lines += 'yml password: <no match>' }
      } else { $lines += 'yml: <absent>' }
      $pgv = Join-Path $TargetDir 'data\pgdata\PG_VERSION'
      if (Test-Path $pgv) {
        $lines += "PG_VERSION: mtime=$((Get-Item $pgv).LastWriteTime.ToString('o')) content=$((Get-Content $pgv -Raw).Trim())"
      } else { $lines += 'PG_VERSION: <absent>' }
      # 检测器单跑探针：与 apply-patch :detect_target 同逻辑
      # （Win32_Service + 注册表两路、归一化、向上两级、去重）。
      $vals = @()
      $sv = Get-CimInstance Win32_Service -Filter "Name='IterationBoardApp'" -ErrorAction SilentlyContinue
      if ($sv -and $sv.PathName) { $vals += $sv.PathName }
      try {
        $r2 = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\IterationBoardApp' -ErrorAction Stop).ImagePath
        if ($r2) { $vals += [Environment]::ExpandEnvironmentVariables($r2) }
      } catch { }
      $roots = @()
      foreach ($v in $vals) {
        $pp = $v.Trim().Trim([char]34)
        if ($pp -match '^(.+?\.exe)') { $pp = $Matches[1] }
        try { $pp = [IO.Path]::GetFullPath($pp) } catch { }
        $dir = Split-Path (Split-Path $pp -Parent) -Parent
        if ($dir -and ($roots -notcontains $dir)) { $roots += $dir }
      }
      $lines += "detector probe: count=$($roots.Count) roots=[$($roots -join ' | ')]"
    }
  } catch {
    $lines += "forensics error: $($_.Exception.Message)"
  }
  $lines | Out-File -Encoding utf8 $f
  Write-Host "forensics written: forensics-$safe.txt"
}
function Uninstall-Quiet([string]$TargetDir) {
  # 卸载纪律（Gemini 终检/咨询两轮采纳项）：严禁 SilentlyContinue
  # 吞掉删除失败——残留的服务登记或 pgdata 正是卡点 A/B 的温床。
  # 顺序：先停两服务 -> 等进程退出 -> 跑夹具自己的 uninstall.bat
  # （其服务操作此时可能无的放矢，容忍其退出码）-> 注销两服务
  # 并确认注销完成 -> 删目录（带重试），删不掉即 FAIL 并留现场。
  foreach ($svc in @('IterationBoardApp', 'IterationBoardPG')) {
    & sc.exe stop $svc 2>$null | Out-Null
  }
  for ($i = 0; $i -lt 30; $i++) {
    $busy = Get-CimInstance Win32_Process -Filter "Name='postgres.exe' or Name='java.exe'" -ErrorAction SilentlyContinue |
      Where-Object { $_.CommandLine -and $_.CommandLine.Contains($TargetDir) }
    $pgBusy = Get-CimInstance Win32_Process -Filter "Name='postgres.exe'" -ErrorAction SilentlyContinue
    if (-not $busy -and -not $pgBusy) { break }
    Start-Sleep -Seconds 2
  }
  $u = Join-Path $TargetDir 'uninstall.bat'
  if (Test-Path $u) { Run-BatAuto $u @('--yes', '--no-pause') 'tmp-uninstall.log' $TargetDir | Out-Null }
  foreach ($svc in @('IterationBoardApp', 'IterationBoardPG')) {
    & sc.exe delete $svc 2>$null | Out-Null
  }
  for ($i = 0; $i -lt 15; $i++) {
    $left = $false
    foreach ($svc in @('IterationBoardApp', 'IterationBoardPG')) {
      & sc.exe query $svc 2>$null | Out-Null
      if ($LASTEXITCODE -eq 0) { $left = $true }
    }
    if (-not $left) { break }
    Start-Sleep -Seconds 2
  }
  for ($i = 0; $i -lt 5; $i++) {
    if (-not (Test-Path $TargetDir)) { break }
    Remove-Item $TargetDir -Recurse -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $TargetDir)) { break }
    Start-Sleep -Seconds 3
  }
  if (Test-Path $TargetDir) {
    Check "uninstall removed $TargetDir completely" $false
    Dump-Forensics "uninstall-leftover-$([IO.Path]::GetFileName($TargetDir))" $TargetDir
  }
}

try {
  # ---------- N1: 补丁目录恶劣路径 × 三种运行方式 ----------
  # 目标：rc4 全量包的标准安装（英文+空格路径）。rc4->1.1.0 补丁的
  # 版本门期望 from=1.1.0-rc4，拿 1.1.0 全新安装当升级对象必然被
  # 版本门拦下（1.1.0 终验实测教训），故 N1 夹具必须是 rc4 安装。
  # 补丁分别从「空格+括号(1)」「中文」「Program Files (x86) 风格」
  # 目录里运行；方式 = 带参 / 无参（服务反查）/ 手动输入（仅
  # space-paren 一家：注销应用服务使反查无果，经 stdin 喂入
  # 「带引号+尾随反斜杠」的拖拽式路径，专测手动分支解析存活）。
  $Rc4Target = Join-Path $Base 'std target\iteration-board-offline-1.1.0-rc4'
  $PatchHomes = @(
    @{ Tag = 'space-paren'; Dir = (Join-Path $Base 'patch test (1)') },
    @{ Tag = 'chinese';     Dir = (Join-Path $Base '补丁 目录') },
    @{ Tag = 'pfx86';       Dir = (Join-Path $Base 'Program Files (x86)\ib patch') }
  )
  if (-not $PatchZip -or -not $Rc4Zip) { Write-Host '::warning::SKIP N1 (patch zip / rc4 fixture zip not provided; N1 activates once the packaging phase supplies -PatchZip and -Rc4Zip)' } else {
  foreach ($ph in $PatchHomes) {
    $modes = @('with-arg', 'no-arg')
    if ($ph.Tag -eq 'space-paren') { $modes += 'manual' }
    foreach ($mode in $modes) {
      $tag = "N1 $($ph.Tag)/$mode"
      Uninstall-Quiet $Rc4Target
      $code = Install-Fresh $Rc4Zip $Rc4Target "n1-$($ph.Tag)-$mode"
      Check "$tag fresh install exit 0" ($code -eq 0)
      Check "$tag fresh install healthy" (Wait-Health)
      New-Item -ItemType Directory -Force -Path $ph.Dir | Out-Null
      Expand-Archive -Path $PatchZip -DestinationPath $ph.Dir -Force
      $patchBat = Get-ChildItem $ph.Dir -Recurse -Filter 'apply-patch.bat' | Select-Object -First 1
      Check "$tag apply-patch.bat found in nasty patch dir" ($null -ne $patchBat)
      if ($patchBat) {
        $before = Get-Date
        if ($mode -eq 'manual') {
          # 注销应用服务 -> 服务反查无果 -> 脚本走手动输入分支；
          # stdin 喂入拖拽式路径（带引号、尾随反斜杠）。服务已
          # 注销，补丁必然在最后的服务重启步失败（exit 1 为
          # 预期），本例只断言手动分支的解析存活：目标被正确
          # 识别、文件拷贝步已到达、日志无 cmd 解析错误字样
          # （N3-1.0.1 手动分支语法错误的回归门）。
          & sc.exe stop IterationBoardApp | Out-Null
          Start-Sleep -Seconds 5
          & sc.exe delete IterationBoardApp | Out-Null
          Start-Sleep -Seconds 2
          $dragPath = '"' + $Rc4Target + '\"'
          $code = Run-BatAuto $patchBat.FullName @() "n1-$($ph.Tag)-$mode.log" $patchBat.Directory.FullName $dragPath
          $elog = Get-Content (Join-Path $Evidence "n1-$($ph.Tag)-$mode.log") -Raw
          Check "$tag manual branch resolved the dragged path" ($elog -match [regex]::Escape("Target install folder: $Rc4Target"))
          Check "$tag manual branch reached the copy step" ($elog -match 'Copying patched files into')
          Check "$tag manual branch free of cmd parse errors" ($elog -notmatch 'syntax of the command' -and $elog -notmatch 'was unexpected at this time')
          Dump-Forensics "n1-$($ph.Tag)-$mode-postpatch" $Rc4Target
          $slog = Latest-ScriptLog $patchBat.Directory.FullName 'apply-patch'
          Check "$tag apply-patch script log written (no flash exit)" ($null -ne $slog -and $slog.LastWriteTime -ge $before -and $slog.Length -gt 200)
        } else {
          $args = @()
          if ($mode -eq 'with-arg') { $args = @($Rc4Target) }
          $code = Run-BatAuto $patchBat.FullName $args "n1-$($ph.Tag)-$mode.log" $patchBat.Directory.FullName
          Check "$tag apply-patch exit 0" ($code -eq 0)
          $slog = Latest-ScriptLog $patchBat.Directory.FullName 'apply-patch'
          Check "$tag apply-patch script log written (no flash exit)" ($null -ne $slog -and $slog.LastWriteTime -ge $before -and $slog.Length -gt 200)
          Check "$tag target upgraded to 1.1.0" (((Get-Content (Join-Path $Rc4Target 'version.txt') -Raw) -match '1.1.0' -and (Get-Content (Join-Path $Rc4Target 'version.txt') -Raw) -notmatch 'rc'))
          Check "$tag app healthy after patch" (Wait-Health)
          Check "$tag /meta version 1.1.0" ((Get-MetaVersion) -eq '1.1.0')
          Dump-Forensics "n1-$($ph.Tag)-$mode-postpatch" $Rc4Target
        }
      }
      Uninstall-Quiet $Rc4Target
    }
  }
  }

  # ---------- N2: 恶劣安装目录（英文+空格+括号）里的运维脚本 ----------
  $NastyTarget = Join-Path $Base 'install (1)\iteration-board-offline-1.1.0'
  $code = Install-Fresh $FullZip $NastyTarget 'n2' 
  Check 'N2 install into space+paren path exit 0' ($code -eq 0)
  Check 'N2 healthy' (Wait-Health)
  $code = Run-BatAuto (Join-Path $NastyTarget 'backup.bat') @('auto') 'n2-backup.log' $NastyTarget
  Check 'N2 backup.bat auto exit 0' ($code -eq 0)
  $code = Run-BatAuto (Join-Path $NastyTarget 'reset-admin.bat') @('--no-pause') 'n2-reset-admin.log' $NastyTarget
  Check 'N2 reset-admin.bat exit 0' ($code -eq 0)
  # Log hygiene (final-verification item 3): the temporary
  # password is displayed on the operator console by design,
  # but it must never land in the persistent log files (the
  # script's own reset-admin-<ts>.log, the application log).
  $pwFile = Join-Path $NastyTarget 'config\admin-initial-password.txt'
  if (Test-Path $pwFile) {
    $pwm = Select-String -Path $pwFile -Pattern 'admin initial password:\s*(\S+)' | Select-Object -First 1
    if ($pwm) {
      $tempPw = $pwm.Matches[0].Groups[1].Value
      $rlog = Latest-ScriptLog $NastyTarget 'reset-admin'
      Check 'N2 reset-admin script log free of the temp password' ($null -ne $rlog -and ((Get-Content $rlog.FullName -Raw) -notmatch [regex]::Escape($tempPw)))
      $appLog = Join-Path $NastyTarget 'logs\app.log'
      Check 'N2 app log free of the temp password' ((-not (Test-Path $appLog)) -or ((Get-Content $appLog -Raw) -notmatch [regex]::Escape($tempPw)))
    }
  }
  $bk = Get-ChildItem (Join-Path $NastyTarget 'backup') -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
  Check 'N2 backup folder created' ($null -ne $bk)
  if ($bk) {
    $code = Run-BatAuto (Join-Path $NastyTarget 'restore.bat') @($bk.Name, '--yes', '--no-pause') 'n2-restore.log' $NastyTarget
    Check 'N2 restore.bat <backup-name> exit 0' ($code -eq 0)
  }
  Check 'N2 healthy after restore' (Wait-Health)
  $blog = Latest-ScriptLog $NastyTarget 'backup'
  Check 'N2 backup script log written' ($null -ne $blog -and $blog.Length -gt 100)
  Uninstall-Quiet $NastyTarget

  # ---------- N3: 夹具老版本补丁升级（1.0.4 / 1.0.1，双击方式） ----------
  foreach ($fx in @(@{ V = '1.0.4'; Zip = $OldZip104; Patch = $Patch104 }, @{ V = '1.0.1'; Zip = $OldZip101; Patch = $Patch101 })) {
    if (-not $fx.Zip -or -not $fx.Patch) { Write-Host "SKIP N3 $($fx.V) (zips not provided)"; continue }
    $tag = "N3 $($fx.V)"
    $target = Join-Path $Base "fixture-$($fx.V)\iteration-board-offline-$($fx.V)"
    Uninstall-Quiet $target
    Dump-Forensics "$tag post-uninstall" $target
    $code = Install-Fresh $fx.Zip $target "n3-$($fx.V)"
    Check "$tag fixture install exit 0" ($code -eq 0)
    Check "$tag fixture healthy" (Wait-HealthLegacy)
    $patchHome = Join-Path $Base "patch fixture (1)"
    New-Item -ItemType Directory -Force -Path $patchHome | Out-Null
    Expand-Archive -Path $fx.Patch -DestinationPath $patchHome -Force
    $patchBat = Get-ChildItem $patchHome -Recurse -Filter 'apply-patch.bat' | Select-Object -First 1
    Check "$tag apply-patch.bat found" ($null -ne $patchBat)
    if ($patchBat) {
      # 不带参数 = 模拟双击：靠服务反查自动检测到夹具安装目录
      $code = Run-BatAuto $patchBat.FullName @() "n3-$($fx.V).log" $patchBat.Directory.FullName
      Check "$tag no-arg apply-patch exit 0 (auto-detect)" ($code -eq 0)
      Check "$tag upgraded to 1.1.0" (((Get-Content (Join-Path $target 'version.txt') -Raw) -match '1.1.0' -and (Get-Content (Join-Path $target 'version.txt') -Raw) -notmatch 'rc'))
      Check "$tag healthy after upgrade" (Wait-Health)
      Check "$tag /meta version 1.1.0" ((Get-MetaVersion) -eq '1.1.0')
      Dump-Forensics "$tag postpatch" $target
    }
    Uninstall-Quiet $target
  }

  # ---------- N4: 终验追加五项（Gemini 终检采纳项） ----------
  # N4a 分卷合并.bat 恶劣路径实跑（追加项 5）：夹具是发布用的
  # 同一份合并.bat（tools/ci/fixtures），矩阵按打包侧同法把
  # EXPECT 戳成本轮 CI 全量包的哈希；分卷由 CI 全量包现切
  # （每卷 98,000,000 字节，与发布分卷同规格）。
  $mergeHome = Join-Path $Base 'merge test (1)'
  New-Item -ItemType Directory -Force -Path $mergeHome | Out-Null
  $zipHash = (Get-FileHash $FullZip -Algorithm SHA256).Hash.ToLower()
  $srcStream = [IO.File]::OpenRead($FullZip)
  try {
    $buf = New-Object byte[] 1048576
    $v1 = [IO.File]::Create((Join-Path $mergeHome 'iteration-board-offline-1.1.0.zip.001'))
    $left = 98000000
    while ($left -gt 0) { $n = $srcStream.Read($buf, 0, [Math]::Min($buf.Length, $left)); if ($n -le 0) { break }; $v1.Write($buf, 0, $n); $left -= $n }
    $v1.Close()
    $v2 = [IO.File]::Create((Join-Path $mergeHome 'iteration-board-offline-1.1.0.zip.002'))
    while (($n = $srcStream.Read($buf, 0, $buf.Length)) -gt 0) { $v2.Write($buf, 0, $n) }
    $v2.Close()
  } finally { $srcStream.Close() }
  $mergeBatText = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'fixtures\合并.bat'))
  $mergeBatText = $mergeBatText -replace 'set "EXPECT=[0-9a-f]{64}"', ('set "EXPECT=' + $zipHash + '"')
  [IO.File]::WriteAllText((Join-Path $mergeHome '合并.bat'), $mergeBatText, [Text.Encoding]::ASCII)
  $code = Run-BatAuto (Join-Path $mergeHome '合并.bat') @() 'n4-merge.log' $mergeHome
  Check 'N4 merge.bat exit 0 in space+paren folder' ($code -eq 0)
  $mergedZip = Join-Path $mergeHome 'iteration-board-offline-1.1.0.zip'
  Check 'N4 merged zip sha256 equals the full zip' ((Test-Path $mergedZip) -and ((Get-FileHash $mergedZip -Algorithm SHA256).Hash.ToLower() -eq $zipHash))

  # N4b 版本门负控（追加项 4）：1.0.4 补丁打 rc4 安装，必须
  # 干净拒绝（明确报期望版本）、目标分毫不动、服务照常健康。
  if (-not $Patch104 -or -not $Rc4Zip) { Write-Host '::warning::SKIP N4 gate negative (patch 1.0.4 / rc4 fixture not provided)' } else {
    Uninstall-Quiet $Rc4Target
    $code = Install-Fresh $Rc4Zip $Rc4Target 'n4-gate'
    Check 'N4 gate-negative fresh rc4 install exit 0' ($code -eq 0)
    Check 'N4 gate-negative rc4 healthy' (Wait-Health)
    $negHome = Join-Path $Base 'patch negctl (1)'
    New-Item -ItemType Directory -Force -Path $negHome | Out-Null
    Expand-Archive -Path $Patch104 -DestinationPath $negHome -Force
    $negBat = Get-ChildItem $negHome -Recurse -Filter 'apply-patch.bat' | Select-Object -First 1
    Check 'N4 gate-negative apply-patch.bat found' ($null -ne $negBat)
    if ($negBat) {
      $code = Run-BatAuto $negBat.FullName @($Rc4Target) 'n4-gate-negative.log' $negBat.Directory.FullName
      Check 'N4 wrong-version patch rejected (exit != 0)' ($code -ne 0)
      $nlog = Get-Content (Join-Path $Evidence 'n4-gate-negative.log') -Raw
      Check 'N4 rejection names the expected version' ($nlog -match 'upgrades version 1\.0\.4 only')
      Check 'N4 target version.txt untouched (still rc4)' (((Get-Content (Join-Path $Rc4Target 'version.txt') -Raw) -match '1\.1\.0-rc4'))
      Check 'N4 target still healthy after rejection' (Wait-Health)
      Dump-Forensics 'n4-gate-postpatch' $Rc4Target
    }
    Uninstall-Quiet $Rc4Target
  }

  # N4c 播种升级 + 全链路回滚（追加项 1、2、3 前半）：1.0.4
  # 夹具预置管理员/普通用户/团队/事项，application.yml 原样
  # 不动打补丁，验 Flyway V11 + 旧账号登录 + 原数据可读 +
  # 升级前 .dump 可用；随后 rollback.bat 一键回滚，验旧版
  # 恢复服务、version.txt 残留清除、Flyway 回 V7、数据完好。
  if (-not $OldZip104 -or -not $Patch104) { Write-Host '::warning::SKIP N4 chain (1.0.4 fixture / patch not provided)' } else {
    $chainTarget = Join-Path $Base 'fixture-chain\iteration-board-offline-1.0.4'
    Uninstall-Quiet $chainTarget
    $code = Install-Fresh $OldZip104 $chainTarget 'n4-chain'
    Check 'N4 chain fixture install exit 0' ($code -eq 0)
    Check 'N4 chain fixture healthy' (Wait-HealthLegacy)
    $cfgHashBefore = (Get-FileHash (Join-Path $chainTarget 'config\application.yml') -Algorithm SHA256).Hash
    $marker = 'N4SEED-' + (Get-Date -Format 'HHmmss')
    $adminS = Api-Register 'n4admin' 'N4 Admin' 'N4Seed1234@'
    Check 'N4 seed admin registered on 1.0.4 (first account)' ($null -ne $adminS)
    $userS = Api-Register 'n4user' 'N4 User' 'N4Seed1234@'
    Check 'N4 seed ordinary user registered on 1.0.4' ($null -ne $userS)
    $teamId = $null
    if ($adminS) {
      $tr = Api-Call $adminS 'Post' '/teams' @{ name = 'N4 Seed Team'; code = 'N4SEED' }
      if ($tr.Body -and $tr.Body.id) { $teamId = [long]$tr.Body.id }
      if ($teamId) {
        $ir = Api-Call $adminS 'Post' "/teams/$teamId/items" @{ type = 'TASK'; title = $marker }
        Check 'N4 seed item created' ($ir.Status -lt 300)
      }
    }
    Check 'N4 seed team created' ($null -ne $teamId)
    $patchHome2 = Join-Path $Base 'patch chain (1)'
    New-Item -ItemType Directory -Force -Path $patchHome2 | Out-Null
    Expand-Archive -Path $Patch104 -DestinationPath $patchHome2 -Force
    $chainBat = Get-ChildItem $patchHome2 -Recurse -Filter 'apply-patch.bat' | Select-Object -First 1
    Check 'N4 chain apply-patch.bat found' ($null -ne $chainBat)
    if ($chainBat) {
      $code = Run-BatAuto $chainBat.FullName @() 'n4-chain-patch.log' $chainBat.Directory.FullName
      Check 'N4 chain patch exit 0 (auto-detect, seeded target)' ($code -eq 0)
    }
    Check 'N4 chain healthy after upgrade' (Wait-Health)
    Check 'N4 chain /meta version 1.1.0' ((Get-MetaVersion) -eq '1.1.0')
    Check 'N4 chain application.yml untouched by the patch' (((Get-FileHash (Join-Path $chainTarget 'config\application.yml') -Algorithm SHA256).Hash) -eq $cfgHashBefore)
    $fly = Invoke-PsqlScalar $chainTarget 'select version from flyway_schema_history where success order by installed_rank desc limit 1'
    Check 'N4 chain Flyway migrated to V11' ($fly -eq '11')
    $adminS2 = Api-Login 'n4admin' 'N4Seed1234@'
    Check 'N4 pre-upgrade admin can still login after upgrade' ($null -ne $adminS2)
    if ($adminS2 -and $teamId) {
      $items = Api-Call $adminS2 'Get' "/teams/$teamId/items?scope=all" $null
      Check 'N4 seeded item readable after upgrade' ($items.Raw -match [regex]::Escape($marker))
    }
    $userS2 = Api-Login 'n4user' 'N4Seed1234@'
    Check 'N4 pre-upgrade ordinary user can still login' ($null -ne $userS2)
    $bundle = Get-ChildItem (Join-Path $chainTarget 'backup') -Directory -Filter 'upgrade-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    Check 'N4 rollback bundle recorded' ($null -ne $bundle)
    if ($bundle) {
      $info = Get-Content (Join-Path $bundle.FullName 'upgrade-info.txt') -Raw
      if ($info -match 'DbBackup=(.+)') {
        $dumpPath = Join-Path $Matches[1].Trim() 'db.dump'
        $listOut = & (Join-Path $chainTarget 'runtime\pgsql\bin\pg_restore.exe') --list $dumpPath 2>&1
        Check 'N4 pre-upgrade dump readable by pg_restore --list' ($LASTEXITCODE -eq 0 -and (($listOut | Out-String) -match 'TABLE'))
      }
    }
    $code = Run-BatAuto (Join-Path $chainTarget 'rollback.bat') @('--yes', '--no-pause') 'n4-rollback.log' $chainTarget
    Check 'N4 rollback.bat exit 0' ($code -eq 0)
    Check 'N4 healthy after rollback (legacy version serving)' (Wait-HealthLegacy)
    Check 'N4 version.txt removed by rollback (legacy layout restored)' (-not (Test-Path (Join-Path $chainTarget 'version.txt')))
    $flyAfter = Invoke-PsqlScalar $chainTarget 'select version from flyway_schema_history where success order by installed_rank desc limit 1'
    Check 'N4 Flyway back at V7 after rollback' ($flyAfter -eq '7')
    $adminS3 = Api-Login 'n4admin' 'N4Seed1234@'
    Check 'N4 admin can login after rollback' ($null -ne $adminS3)
    if ($adminS3 -and $teamId) {
      $items = Api-Call $adminS3 'Get' "/teams/$teamId/items?scope=all" $null
      Check 'N4 seeded item intact after rollback (business data preserved)' ($items.Raw -match [regex]::Escape($marker))
    }
    Dump-Forensics 'n4-chain-final' $chainTarget
    Uninstall-Quiet $chainTarget
  }
} catch {
  Write-Host "::error::NASTY FATAL: $($_.Exception.Message)"
  $script:Failures += "FATAL: $($_.Exception.Message)"
} finally {
  if ($script:Failures.Count -gt 0) {
    Write-Host "NASTY-PATH RESULT: $($script:Failures.Count) FAILURE(S)"
    $script:Failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
  }
  Write-Host 'NASTY-PATH RESULT: ALL PASS'
  exit 0
}
