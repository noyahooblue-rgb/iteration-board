# ============================================================
# Regression test: migrate-import.bat settings-apply command
# (root-cause ledger entry 8).
#
# The .bat rewrites the target config\application.yml with the
# exported port and AI connection values through one inline
# powershell -Command. Its old replacement strings were built
# as '$1' + value: for the port that produced the literal text
# $118080 in the file (.NET parses $ followed by digits as ONE
# group number; an unknown group number stays literal), which
# broke YAML parsing at application start and failed P13.
#
# This test extracts the powershell command VERBATIM from
# packaging/windows/migrate-import.bat (between the
# IB-SETTINGS-COMMAND-BEGIN / END markers) and runs it in a
# child process of the same PowerShell host against fixture
# configs, so what is tested is byte-for-byte what ships.
#
# Cases:
#   1. Non-default port 19090 plus adversarial values (a model
#      name starting with a digit, an api-key containing the
#      substitution tokens $& and $$) must be applied exactly,
#      the target password must survive, exit code 0.
#   2. A target config without a port line must be rejected by
#      the command's own validation (exit code 1) instead of
#      being written and started blindly.
#
# Run: powershell -NoProfile -File tools/ci/test-migrate-settings.ps1
# Exit code 0 = all checks passed.
# ============================================================
[CmdletBinding()]
param(
    [string]$RepoRoot = (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent)
)

$ErrorActionPreference = 'Stop'
$script:Failures = @()

function Check([string]$Name, [bool]$Ok) {
    if ($Ok) {
        Write-Host "[PASS] $Name"
    } else {
        Write-Host "[FAIL] $Name"
        $script:Failures += $Name
    }
}

# --- extract the shipped command verbatim --------------------
$batPath = Join-Path $RepoRoot 'packaging\windows\migrate-import.bat'
$batLines = [IO.File]::ReadAllLines($batPath)
$begin = -1
$end = -1
for ($i = 0; $i -lt $batLines.Count; $i++) {
    if ($batLines[$i] -match 'IB-SETTINGS-COMMAND-BEGIN') { $begin = $i }
    if ($batLines[$i] -match 'IB-SETTINGS-COMMAND-END') { $end = $i }
}
if ($begin -lt 0 -or $end -le $begin) {
    Write-Host '[FAIL] IB-SETTINGS-COMMAND markers not found in migrate-import.bat'
    exit 1
}
$command = $null
for ($i = $begin + 1; $i -lt $end; $i++) {
    if ($batLines[$i] -match '^\s*powershell -NoProfile -Command "(.*)"\s*$') {
        $command = $Matches[1]
        break
    }
}
if (-not $command) {
    Write-Host '[FAIL] settings-apply powershell command not found between the markers'
    exit 1
}
Write-Host "[INFO] extracted settings-apply command ($($command.Length) chars) from migrate-import.bat"

# --- fixtures --------------------------------------------------
$exportedYml = @'
# exported business config (password removed by migrate-export)
server:
  port: 19090
  servlet:
    session:
      timeout: 8h

spring:
  application:
    name: iteration-board
  datasource:
    url: jdbc:postgresql://127.0.0.1:15432/iteration_board
    username: postgres
    password: ""
  jpa:
    hibernate:
      ddl-auto: validate

ai:
  base-url: "https://api.example.com/v1"
  api-key: "sk-test$&key$$x"
  text-model: "5.2-preview"
  vision-model: "vision-2"
  mode: live
  timeout-seconds: 60

backup:
  dir: ""
'@

$targetYml = @'
# target installation config
server:
  port: 18080
  servlet:
    session:
      timeout: 8h

spring:
  application:
    name: iteration-board
  datasource:
    url: jdbc:postgresql://127.0.0.1:15432/iteration_board
    username: postgres
    password: <redacted>
  jpa:
    hibernate:
      ddl-auto: validate

ai:
  base-url: ""
  api-key: ""
  text-model: ""
  vision-model: ""
  mode: mock
  timeout-seconds: 60

backup:
  dir: ""
'@

$targetNoPortYml = @'
server:
  servlet:
    session:
      timeout: 8h
spring:
  datasource:
    password: <redacted>
ai:
  base-url: ""
  api-key: ""
  text-model: ""
  vision-model: ""
  mode: mock
'@

function Write-Fixture([string]$Path, [string]$Text) {
    $crlf = $Text -replace '\r?\n', "`r`n"
    [IO.File]::WriteAllText($Path, $crlf, [Text.Encoding]::ASCII)
}

function Invoke-SettingsCommand([string]$OldPath, [string]$NewPath) {
    $psExe = (Get-Process -Id $PID).Path
    $env:IB_OLD = $OldPath
    $env:IB_NEW = $NewPath
    try {
        $out = & $psExe -NoProfile -Command $command 2>&1 | Out-String
        return @{ Code = $LASTEXITCODE; Output = $out }
    } finally {
        Remove-Item Env:IB_OLD -ErrorAction SilentlyContinue
        Remove-Item Env:IB_NEW -ErrorAction SilentlyContinue
    }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ('ib-settings-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $oldPath = Join-Path $work 'exported.yml'
    Write-Fixture $oldPath $exportedYml

    # --- case 1: non-default port + adversarial values ---------
    $newPath = Join-Path $work 'target.yml'
    Write-Fixture $newPath $targetYml
    $r1 = Invoke-SettingsCommand $oldPath $newPath
    Check 'case 1: command exits 0' ($r1.Code -eq 0)
    $lines = [IO.File]::ReadAllText($newPath) -split '\r?\n'
    Check 'case 1: port line is exactly the exported non-default port' ($lines -contains '  port: 19090')
    $leak = $lines | Where-Object { $_ -match '^\s*\$\d' }
    Check 'case 1: no line is a leaked $ substitution token' ($null -eq $leak)
    Check 'case 1: text-model starting with a digit applied verbatim' ($lines -contains '  text-model: "5.2-preview"')
    Check 'case 1: api-key with $ tokens applied verbatim' ($lines -contains '  api-key: "sk-test$&key$$x"')
    Check 'case 1: base-url applied verbatim' ($lines -contains '  base-url: "https://api.example.com/v1"')
    Check 'case 1: mode applied verbatim' ($lines -contains '  mode: live')
    Check 'case 1: target database password preserved' ($lines -contains '    password: <redacted>')

    # --- case 2: target without a port line must be rejected ---
    $noPortPath = Join-Path $work 'target-noport.yml'
    Write-Fixture $noPortPath $targetNoPortYml
    $r2 = Invoke-SettingsCommand $oldPath $noPortPath
    Check 'case 2: command rejects a target config with no port line (exit 1)' ($r2.Code -eq 1)
    Check 'case 2: rejection message names the problem' ($r2.Output -match 'port')
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:Failures.Count -gt 0) {
    Write-Host "[RESULT] FAILED: $($script:Failures.Count) check(s): $($script:Failures -join '; ')"
    exit 1
}
Write-Host '[RESULT] all checks passed'
exit 0
