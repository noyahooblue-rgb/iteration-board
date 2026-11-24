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
#     [-PatchZip C:\ibdl\iteration-board-patch-1.1.0-rc4-to-1.1.0.zip] `
#     [-OldZip104 C:\ibdl\iteration-board-offline-1.0.4.zip -Patch104 C:\ibdl\iteration-board-patch-1.0.4-to-1.1.0.zip] `
#     [-OldZip101 C:\ibdl\iteration-board-offline-1.0.1.zip -Patch101 C:\ibdl\iteration-board-patch-1.0.1-to-1.1.0.zip]
param(
  [Parameter(Mandatory = $true)][string]$FullZip,
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
function Run-BatAuto([string]$BatPath, [string[]]$BatArgs, [string]$LogName, [string]$Cwd) {
  # IB_AUTO/IB_YES 下运行 .bat，返回退出码；控制台输出落证据目录。
  $env:IB_AUTO = '1'; $env:IB_YES = '1'
  $out = Join-Path $Evidence $LogName
  Push-Location $Cwd
  try {
    & cmd /c "`"$BatPath`"" @BatArgs *>&1 | Out-File -Encoding utf8 $out
    return $LASTEXITCODE
  } finally { Pop-Location }
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
  $code = Run-BatAuto (Join-Path $TargetDir 'install.bat') @('--no-pause') 'tmp-install.log' $TargetDir
  return $code
}
function Uninstall-Quiet([string]$TargetDir) {
  $u = Join-Path $TargetDir 'uninstall.bat'
  if (Test-Path $u) { Run-BatAuto $u @('--yes', '--no-pause') 'tmp-uninstall.log' $TargetDir | Out-Null }
  Start-Sleep -Seconds 3
  Remove-Item $TargetDir -Recurse -Force -ErrorAction SilentlyContinue
}

try {
  # ---------- N1: 补丁目录恶劣路径 × 两种运行方式 ----------
  # 目标：英文+空格路径的标准安装；补丁分别从「空格+括号(1)」「中文」
  # 「Program Files (x86) 风格」目录里运行。
  $StdTarget = Join-Path $Base 'std target\iteration-board-offline-1.1.0'
  $PatchHomes = @(
    @{ Tag = 'space-paren'; Dir = (Join-Path $Base 'patch test (1)') },
    @{ Tag = 'chinese';     Dir = (Join-Path $Base '补丁 目录') },
    @{ Tag = 'pfx86';       Dir = (Join-Path $Base 'Program Files (x86)\ib patch') }
  )
  if (-not $PatchZip) { Write-Host '::warning::SKIP N1 (patch zip not provided; N1 activates once the packaging phase supplies -PatchZip)' } else {
  foreach ($ph in $PatchHomes) {
    foreach ($mode in @('with-arg', 'no-arg')) {
      $tag = "N1 $($ph.Tag)/$mode"
      Uninstall-Quiet $StdTarget
      $code = Install-Fresh $FullZip $StdTarget
      Check "$tag fresh install exit 0" ($code -eq 0)
      Check "$tag fresh install healthy" (Wait-Health)
      New-Item -ItemType Directory -Force -Path $ph.Dir | Out-Null
      Expand-Archive -Path $PatchZip -DestinationPath $ph.Dir -Force
      $patchBat = Get-ChildItem $ph.Dir -Recurse -Filter 'apply-patch.bat' | Select-Object -First 1
      Check "$tag apply-patch.bat found in nasty patch dir" ($null -ne $patchBat)
      if ($patchBat) {
        $args = @()
        if ($mode -eq 'with-arg') { $args = @($StdTarget) }
        $before = Get-Date
        $code = Run-BatAuto $patchBat.FullName $args "n1-$($ph.Tag)-$mode.log" $patchBat.Directory.FullName
        Check "$tag apply-patch exit 0" ($code -eq 0)
        $slog = Latest-ScriptLog $patchBat.Directory.FullName 'apply-patch'
        Check "$tag apply-patch script log written (no flash exit)" ($null -ne $slog -and $slog.LastWriteTime -ge $before -and $slog.Length -gt 200)
        Check "$tag target upgraded to 1.1.0" (((Get-Content (Join-Path $StdTarget 'version.txt') -Raw) -match '1.1.0' -and (Get-Content (Join-Path $StdTarget 'version.txt') -Raw) -notmatch 'rc'))
        Check "$tag app healthy after patch" (Wait-Health)
        Check "$tag /meta version 1.1.0" ((Get-MetaVersion) -eq '1.1.0')
      }
      Uninstall-Quiet $StdTarget
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
