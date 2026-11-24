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
function Install-Fresh([string]$Zip, [string]$TargetDir) {
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
  $code = Run-BatAuto (Join-Path $TargetDir 'install.bat') @('--no-pause') 'tmp-install.log' $TargetDir $answers
  return $code
}
function Uninstall-Quiet([string]$TargetDir) {
  $u = Join-Path $TargetDir 'uninstall.bat'
  if (Test-Path $u) { Run-BatAuto $u @('--yes', '--no-pause') 'tmp-uninstall.log' $TargetDir | Out-Null }
  Start-Sleep -Seconds 3
  Remove-Item $TargetDir -Recurse -Force -ErrorAction SilentlyContinue
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
      $code = Install-Fresh $Rc4Zip $Rc4Target
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
        }
      }
      Uninstall-Quiet $Rc4Target
    }
  }
  }

  # ---------- N2: 恶劣安装目录（英文+空格+括号）里的运维脚本 ----------
  $NastyTarget = Join-Path $Base 'install (1)\iteration-board-offline-1.1.0'
  $code = Install-Fresh $FullZip $NastyTarget
  Check 'N2 install into space+paren path exit 0' ($code -eq 0)
  Check 'N2 healthy' (Wait-Health)
  $code = Run-BatAuto (Join-Path $NastyTarget 'backup.bat') @('auto') 'n2-backup.log' $NastyTarget
  Check 'N2 backup.bat auto exit 0' ($code -eq 0)
  $code = Run-BatAuto (Join-Path $NastyTarget 'reset-admin.bat') @('--no-pause') 'n2-reset-admin.log' $NastyTarget
  Check 'N2 reset-admin.bat exit 0' ($code -eq 0)
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
    $target = Join-Path $Base "fixture $($fx.V)\iteration-board-offline-$($fx.V)"
    Uninstall-Quiet $target
    $code = Install-Fresh $fx.Zip $target
    Check "$tag fixture install exit 0" ($code -eq 0)
    Check "$tag fixture healthy" (Wait-Health)
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
    }
    Uninstall-Quiet $target
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
